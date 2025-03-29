package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.ChunkInfo;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class AsyncFileMergeService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncFileMergeService.class);
    private final ConcurrentMap<String, MergeTask> mergeTasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ApplicationSettingsService applicationSettingsService;
    private final FileEncryptionService fileEncryptionService;
    private final FileService fileService;

    private final File tempDir = new File(System.getProperty("java.io.tmpdir"));
    private final FileRepository fileRepository;

    public AsyncFileMergeService(ApplicationSettingsService applicationSettingsService,
                                 FileEncryptionService fileEncryptionService,
                                 FileService fileService, FileRepository fileRepository) {
        this.applicationSettingsService = applicationSettingsService;
        this.fileEncryptionService = fileEncryptionService;
        this.fileService = fileService;
        this.fileRepository = fileRepository;
    }

    public FileEntity submitChunk(FileUploadRequest request, MultipartFile multipartChunk, int chunkNumber) throws IOException {
        File savedChunk = new File(tempDir, request.fileName + "_chunk_" + chunkNumber);
        multipartChunk.transferTo(savedChunk);
        logger.info("Chunk {} for file {} saved to {}", chunkNumber, request.fileName, savedChunk.getAbsolutePath());

        MergeTask mergeTask = mergeTasks.computeIfAbsent(request.fileName, key -> {
            MergeTask task = new MergeTask(request);
            executorService.submit(task);
            return task;
        });
        boolean isLastChunk = (chunkNumber == request.totalChunks - 1);
        mergeTask.enqueueChunk(new ChunkInfo(chunkNumber, savedChunk, isLastChunk));

        if (isLastChunk) {
            try {
                return mergeTask.getMergeCompletionFuture().get();
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error waiting for merge completion: {}", e.getMessage());
                Thread.currentThread().interrupt();
                throw new IOException("Merge task interrupted", e);
            }
        }
        return null;
    }

    private void cleanUpChunks(FileUploadRequest request) {
        for (int i = 0; i < request.totalChunks; i++) {
            File chunkFile = new File(tempDir, request.fileName + "_chunk_" + i);
            if (chunkFile.exists() && !chunkFile.delete()) {
                logger.warn("Failed to delete chunk file: {}", chunkFile.getAbsolutePath());
            }
            logger.info("Cleaning up chunk {}", i);
        }
    }

    private class MergeTask implements Runnable {

        private final BlockingQueue<ChunkInfo> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<FileEntity> mergeCompletionFuture = new CompletableFuture<>();
        private final FileUploadRequest request;
        private int processedChunks = 0;
        private String uuid;

        MergeTask(FileUploadRequest request) {
            this.request = request;
            do {
                uuid = UUID.randomUUID().toString();
            } while (fileRepository.findByUUID(uuid).isPresent());
        }

        public void enqueueChunk(ChunkInfo chunkInfo) {
            queue.add(chunkInfo);
        }

        public CompletableFuture<FileEntity> getMergeCompletionFuture() {
            return mergeCompletionFuture;
        }

        @Override
        public void run() {
            File finalFile = Paths.get(applicationSettingsService.getFileStoragePath(), uuid).toFile();

            try (OutputStream finalOut = fileService.shouldEncrypt(request) ?
                    fileEncryptionService.getEncryptedOutputStream(finalFile, request.password) :
                    new BufferedOutputStream(new FileOutputStream(finalFile, true))) {

                while (processedChunks < request.totalChunks) {
                    ChunkInfo info = queue.take();
                    try (InputStream in = new BufferedInputStream(new FileInputStream(info.chunkFile))) {
                        in.transferTo(finalOut);
                    }

                    if (!info.chunkFile.delete()) {
                        logger.warn("Failed to delete chunk file: {}", info.chunkFile.getAbsolutePath());
                    }

                    processedChunks++;
                    logger.info("Merged chunk {} for file {}", info.chunkNumber, request.fileName);
                    if (info.isLastChunk) {
                        break;
                    }
                }
                logger.info("All {} chunks merged for file {}", request.totalChunks, request.fileName);

                FileEntity fileEntity = fileService.saveFile(finalFile, request, uuid);
                if (fileEntity != null) {
                    logger.info("File {} saved successfully with UUID {}", request.fileName, fileEntity.uuid);
                } else {
                    logger.error("Saving file {} failed", request.fileName);
                }
                mergeCompletionFuture.complete(fileEntity);
            } catch (Exception e) {
                logger.error("Error merging chunks for file {}: {}", request.fileName, e.getMessage());
                mergeCompletionFuture.completeExceptionally(e);
                cleanUpChunks(request);
                e.printStackTrace();
            } finally {
                mergeTasks.remove(request.fileName);
            }
        }
    }
}