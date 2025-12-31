// metadata.js
// Central registry for client-side metadata stripping
(function () {
  const MB_LIMIT = 25; // matches METADATA_STRIP_MAX_BYTES in upload flow
  const MAX_BYTES = MB_LIMIT * 1024 * 1024;

  const handlers = [];

  const readFileAsDataUrl = (file) =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });

  const loadImage = async (file) => {
    if (window.createImageBitmap) {
      try {
        const bitmap = await createImageBitmap(file);
        return {
          width: bitmap.width,
          height: bitmap.height,
          draw: (ctx) => ctx.drawImage(bitmap, 0, 0),
        };
      } catch (err) {
        console.warn("createImageBitmap failed; falling back to Image()", err);
      }
    }

    const dataUrl = await readFileAsDataUrl(file);
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () =>
        resolve({
          width: img.width,
          height: img.height,
          draw: (ctx) => ctx.drawImage(img, 0, 0),
        });
      img.onerror = reject;
      img.src = dataUrl;
    });
  };

  const canvasReencode = async (file, { mimeHint, quality }) => {
    const image = await loadImage(file);
    const canvas = document.createElement("canvas");
    canvas.width = image.width;
    canvas.height = image.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Canvas unsupported");
    image.draw(ctx);

    const targetType = mimeHint || file.type || "image/png";
    const blob = await new Promise((resolve, reject) => {
      canvas.toBlob(
        (b) => (b ? resolve(b) : reject(new Error("toBlob failed"))),
        targetType,
        quality
      );
    });

    return new File([blob], file.name || "image", {
      type: blob.type || targetType,
    });
  };

  const baseResult = (file) => ({
    processedFile: file,
    supported: false,
    stripped: false,
    wasModified: false,
    warnings: [],
    errors: [],
    metadataRemoved: [],
    failureReason: null,
  });

  const register = (handler) => {
    handlers.push(handler);
  };

  const listSupported = () =>
    handlers.map((h) => ({
      key: h.key,
      extensions: h.extensions || [],
      mimeTypes: h.mimeTypes || [],
      label: h.label,
      family: h.family || "Other",
    }));

  const supportedDisplay = () => {
    const groups = {};
    listSupported().forEach((h) => {
      const fam = h.family || "Other";
      if (!groups[fam]) groups[fam] = [];
      groups[fam].push(h.label || h.extensions.join("/"));
    });

    return Object.entries(groups)
      .map(([family, labels]) => `${family}: ${labels.sort().join(", ")}`)
      .join("; ");
  };

  const emptyCoreXml =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"></cp:coreProperties>';

  const emptyAppXml =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"></Properties>';

  const buildOoxmlHandler = ({
    key,
    label,
    extensions,
    mimeTypes,
    defaultName,
    mimeType,
  }) => ({
    key,
    label,
    family: "Documents",
    extensions,
    mimeTypes,
    strip: async ({ file, name }) => {
      if (!window.JSZip) {
        return {
          processedFile: file,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["engine-unavailable"],
          metadataRemoved: [],
          failureReason: `${label} engine unavailable`,
        };
      }

      const zip = await JSZip.loadAsync(file);
      const corePath = "docProps/core.xml";
      const customPath = "docProps/custom.xml";
      const appPath = "docProps/app.xml";
      const contentTypesPath = "[Content_Types].xml";

      if (zip.file(corePath)) {
        zip.file(corePath, emptyCoreXml);
      }

      if (zip.file(customPath)) {
        zip.remove(customPath);
        const ctFile = zip.file(contentTypesPath);
        if (ctFile) {
          const xml = await ctFile.async("string");
          const cleaned = xml.replace(
            /<Override[^>]+PartName="\/docProps\/custom\.xml"[^>]*\/>/i,
            ""
          );
          zip.file(contentTypesPath, cleaned);
        }
      }

      if (zip.file(appPath)) {
        zip.file(appPath, emptyAppXml);
      }

      const outputBlob = await zip.generateAsync({ type: "blob" });
      const processedFile = new File([outputBlob], name || defaultName, {
        type: mimeType,
      });

      return {
        processedFile,
        stripped: true,
        wasModified: true,
        warnings: [
          "Core/custom/app properties cleared; embedded content may still contain metadata.",
        ],
        errors: [],
        metadataRemoved: [
          "core properties",
          "custom properties",
          "app properties",
        ],
        failureReason: null,
      };
    },
  });

  const buildOdfHandler = ({
    key,
    label,
    extensions,
    mimeTypes,
    defaultName,
    mimeType,
  }) => ({
    key,
    label,
    family: "Documents",
    extensions,
    mimeTypes,
    strip: async ({ file, name }) => {
      if (!window.JSZip) {
        return {
          processedFile: file,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["engine-unavailable"],
          metadataRemoved: [],
          failureReason: `${label} engine unavailable`,
        };
      }

      const zip = await JSZip.loadAsync(file);
      const metaPath = "meta.xml";
      const settingsPath = "settings.xml";

      const emptyMeta =
        '<?xml version="1.0" encoding="UTF-8"?>' +
        '<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" xmlns:ooo="http://openoffice.org/2004/office" office:version="1.2">' +
        "<office:meta>" +
        "<dc:creator></dc:creator>" +
        "<dc:date>1970-01-01T00:00:00Z</dc:date>" +
        "<meta:initial-creator></meta:initial-creator>" +
        "<meta:creation-date>1970-01-01T00:00:00Z</meta:creation-date>" +
        "<meta:editing-cycles>0</meta:editing-cycles>" +
        "<meta:editing-duration>PT0S</meta:editing-duration>" +
        "<meta:generator></meta:generator>" +
        "</office:meta>" +
        "</office:document-meta>";

      if (zip.file(metaPath)) {
        zip.file(metaPath, emptyMeta);
      }

      // settings.xml can leak editor paths; wipe conservatively if present
      if (zip.file(settingsPath)) {
        const emptySettings =
          '<?xml version="1.0" encoding="UTF-8"?>' +
          '<office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0" office:version="1.2">' +
          "<office:settings></office:settings>" +
          "</office:document-settings>";
        zip.file(settingsPath, emptySettings);
      }

      const outputBlob = await zip.generateAsync({ type: "blob" });
      const processedFile = new File([outputBlob], name || defaultName, {
        type: mimeType,
      });

      return {
        processedFile,
        stripped: true,
        wasModified: true,
        warnings: [
          "Rebuilt meta/settings; embedded objects may retain metadata.",
        ],
        errors: [],
        metadataRemoved: ["meta.xml", "settings.xml"],
        failureReason: null,
      };
    },
  });

  const sanitizeOpfXml = (opfXml) => {
    try {
      const parser = new DOMParser();
      const doc = parser.parseFromString(opfXml, "application/xml");
      const metadata = doc.getElementsByTagName("metadata")[0];
      if (!metadata) return opfXml;

      const removeAll = (tag) => {
        const nodes = metadata.getElementsByTagName(tag);
        while (nodes.length) {
          const n = nodes[0];
          n.parentNode?.removeChild(n);
        }
      };

      // Remove common identifying fields
      ["creator", "contributor", "publisher"].forEach((tag) => {
        removeAll(`dc:${tag}`);
        removeAll(tag); // fallback if namespace missing
      });

      // Neutralize identifier content while preserving structure/ids
      const identifiers = metadata.getElementsByTagName("dc:identifier");
      for (let i = 0; i < identifiers.length; i++) {
        identifiers[i].textContent =
          "urn:uuid:00000000-0000-0000-0000-000000000000";
      }
      const plainIdentifiers = metadata.getElementsByTagName("identifier");
      for (let i = 0; i < plainIdentifiers.length; i++) {
        plainIdentifiers[i].textContent =
          "urn:uuid:00000000-0000-0000-0000-000000000000";
      }

      // Remove generator/tool traces if present in <meta> elements
      const metaEls = metadata.getElementsByTagName("meta");
      for (let i = metaEls.length - 1; i >= 0; i--) {
        const el = metaEls[i];
        const name =
          el.getAttribute("name") || el.getAttribute("property") || "";
        if (/generator|creator|contributor|publisher/i.test(name)) {
          el.parentNode?.removeChild(el);
        }
      }

      const serializer = new XMLSerializer();
      return serializer.serializeToString(doc);
    } catch (e) {
      console.warn("Failed to sanitize OPF", e);
      return opfXml;
    }
  };

  const buildEpubHandler = () => ({
    key: "epub",
    label: "EPUB",
    family: "Documents",
    extensions: [".epub"],
    mimeTypes: ["application/epub+zip"],
    strip: async ({ file, name }) => {
      if (!window.JSZip) {
        return {
          processedFile: file,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["engine-unavailable"],
          metadataRemoved: [],
          failureReason: "EPUB engine unavailable",
        };
      }

      const zip = await JSZip.loadAsync(file);

      const findOpfPath = async () => {
        const containerFile = zip.file("META-INF/container.xml");
        if (containerFile) {
          try {
            const xml = await containerFile.async("string");
            const doc = new DOMParser().parseFromString(xml, "application/xml");
            const rootfile = doc.getElementsByTagName("rootfile")[0];
            const fullPath =
              rootfile?.getAttribute("full-path") ||
              rootfile?.getAttribute("fullPath");
            if (fullPath) return fullPath;
          } catch (e) {
            console.warn("Failed to parse container.xml", e);
          }
        }
        const opfFiles = zip.file(/\.opf$/i);
        if (opfFiles && opfFiles.length > 0) {
          return opfFiles[0].name;
        }
        return null;
      };

      const opfPath = await findOpfPath();
      if (!opfPath) {
        return {
          processedFile: file,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["opf-missing"],
          metadataRemoved: [],
          failureReason: "EPUB OPF not found",
        };
      }

      let opfXml;
      try {
        opfXml = await zip.file(opfPath).async("string");
      } catch (e) {
        console.warn("Failed to read OPF", e);
        return {
          processedFile: file,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["opf-read-failed"],
          metadataRemoved: [],
          failureReason: "EPUB OPF read failed",
        };
      }

      const sanitized = sanitizeOpfXml(opfXml);
      zip.file(opfPath, sanitized);

      const outputBlob = await zip.generateAsync({ type: "blob" });
      const processedFile = new File([outputBlob], name || "upload.epub", {
        type: "application/epub+zip",
      });

      return {
        processedFile,
        stripped: true,
        wasModified: true,
        warnings: [
          "OPF metadata cleared (creator/contributor/publisher/generator); identifiers neutralized. Embedded files may retain metadata.",
        ],
        errors: [],
        metadataRemoved: ["content.opf metadata"],
        failureReason: null,
      };
    },
  });

  const buildSvgHandler = () => ({
    key: "svg",
    label: "SVG",
    family: "Graphics",
    extensions: [".svg"],
    mimeTypes: ["image/svg+xml"],
    strip: async ({ file, name }) => {
      try {
        const text = await file.text();
        const parser = new DOMParser();
        const doc = parser.parseFromString(text, "image/svg+xml");

        // Remove <metadata> blocks
        doc.querySelectorAll("metadata").forEach((n) => n.remove());

        // Remove editor-specific elements and attributes (Inkscape/Illustrator traces)
        const editorNamespaces = [
          "inkscape",
          "sodipodi",
          "adobe",
          "illustrator",
          "i:pgf",
        ];
        editorNamespaces.forEach((ns) => {
          doc.querySelectorAll(`[${ns}\\:*]`).forEach((n) => n.remove());
          doc.querySelectorAll(`*[*|*]`).forEach((el) => {
            Array.from(el.attributes).forEach((attr) => {
              if (attr.name.startsWith(`${ns}:`)) el.removeAttribute(attr.name);
            });
          });
        });

        // Remove desc/title text that often carries author info
        doc.querySelectorAll("desc, title").forEach((n) => n.remove());

        // Strip obvious identifying comments
        const walker = doc.createTreeWalker(doc, NodeFilter.SHOW_COMMENT, null);
        const comments = [];
        let c;
        while ((c = walker.nextNode())) comments.push(c);
        comments.forEach((node) => node.parentNode?.removeChild(node));

        const serializer = new XMLSerializer();
        const sanitized = serializer.serializeToString(doc);
        const processedFile = new File([sanitized], name || "upload.svg", {
          type: "image/svg+xml",
        });

        return {
          processedFile,
          stripped: true,
          wasModified: true,
          warnings: [
            "Removed SVG metadata/comments; rendering should remain intact.",
          ],
          errors: [],
          metadataRemoved: [
            "metadata elements",
            "desc/title",
            "editor traces",
            "comments",
          ],
          failureReason: null,
        };
      } catch (e) {
        console.warn("SVG sanitize failed", e);
        return {
          processedFile: file,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["svg-strip-failed"],
          metadataRemoved: [],
          failureReason: "SVG strip failed",
        };
      }
    },
  });

  const matchesHandler = (handler, ext, mime) => {
    const extHit = (handler.extensions || []).some((e) => ext.endsWith(e));
    const mimeHit = (handler.mimeTypes || []).some((m) => mime === m);
    return extHit || mimeHit;
  };

  const stripIfPossible = async (file, displayName = "") => {
    const name = displayName || file.name || "";
    const lowerName = name.toLowerCase();
    const mime = file.type || "";

    const res = baseResult(file);

    const handler = handlers.find((h) => matchesHandler(h, lowerName, mime));
    if (!handler) {
      res.errors.push("unsupported");
      res.failureReason = "Unsupported type";
      return res;
    }

    res.supported = true;

    if (file.size > MAX_BYTES) {
      res.warnings.push(`Too large for stripping (${MB_LIMIT} MB limit)`);
      res.failureReason = res.warnings[0];
      return res;
    }

    try {
      const result = await handler.strip({
        file,
        name,
        mime,
        maxBytes: MAX_BYTES,
      });
      return { ...res, ...result, supported: true };
    } catch (e) {
      console.error(`${handler.key} metadata stripping failed`, e);
      res.errors.push("failed");
      res.failureReason = "Strip failed";
      return res;
    }
  };

  // --- Handlers ---

  register({
    key: "pdf",
    label: "PDF",
    family: "Documents",
    extensions: [".pdf"],
    mimeTypes: ["application/pdf"],
    strip: async ({ file, name }) => {
      if (!window.PDFLib || !PDFLib.PDFDocument) {
        return {
          processedFile: file,
          supported: true,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["engine-unavailable"],
          metadataRemoved: [],
          failureReason: "PDF engine unavailable",
        };
      }

      const arrayBuffer = await file.arrayBuffer();
      const pdfDoc = await PDFLib.PDFDocument.load(arrayBuffer, {
        ignoreEncryption: true,
        updateMetadata: false,
      });

      pdfDoc.setTitle("");
      pdfDoc.setAuthor("");
      pdfDoc.setSubject("");
      pdfDoc.setKeywords([]);
      pdfDoc.setProducer("");
      pdfDoc.setCreator("");
      try {
        pdfDoc.setCreationDate(new Date(0));
        pdfDoc.setModificationDate(new Date(0));
      } catch (_) {
        /* noop */
      }
      try {
        pdfDoc.setXmpMetadata("");
      } catch (_) {
        /* noop */
      }

      const strippedBytes = await pdfDoc.save({ updateMetadata: false });
      const strippedFile = new File([strippedBytes], name || "upload.pdf", {
        type: "application/pdf",
      });

      return {
        processedFile: strippedFile,
        stripped: true,
        wasModified: true,
        warnings: [
          "Rebuilt PDF without doc info; some embedded metadata (e.g., within attachments or streams) may remain.",
        ],
        errors: [],
        metadataRemoved: ["info dictionary", "XMP"],
        failureReason: null,
      };
    },
  });

  register(
    buildOoxmlHandler({
      key: "docx",
      label: "DOCX",
      extensions: [".docx"],
      mimeTypes: [
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      ],
      defaultName: "upload.docx",
      mimeType:
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    })
  );

  register(
    buildOoxmlHandler({
      key: "pptx",
      label: "PPTX",
      extensions: [".pptx"],
      mimeTypes: [
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      ],
      defaultName: "upload.pptx",
      mimeType:
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    })
  );

  register(
    buildOoxmlHandler({
      key: "xlsx",
      label: "XLSX",
      extensions: [".xlsx"],
      mimeTypes: [
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      ],
      defaultName: "upload.xlsx",
      mimeType:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    })
  );

  register(buildEpubHandler());

  register(buildSvgHandler());

  register({
    key: "odt",
    label: "ODT",
    extensions: [".odt"],
    mimeTypes: ["application/vnd.oasis.opendocument.text"],
    strip: buildOdfHandler({
      key: "odt",
      label: "ODT",
      extensions: [".odt"],
      mimeTypes: ["application/vnd.oasis.opendocument.text"],
      defaultName: "upload.odt",
      mimeType: "application/vnd.oasis.opendocument.text",
    }).strip,
  });

  register(
    buildOdfHandler({
      key: "ods",
      label: "ODS",
      extensions: [".ods"],
      mimeTypes: ["application/vnd.oasis.opendocument.spreadsheet"],
      defaultName: "upload.ods",
      mimeType: "application/vnd.oasis.opendocument.spreadsheet",
    })
  );

  register(
    buildOdfHandler({
      key: "odp",
      label: "ODP",
      extensions: [".odp"],
      mimeTypes: ["application/vnd.oasis.opendocument.presentation"],
      defaultName: "upload.odp",
      mimeType: "application/vnd.oasis.opendocument.presentation",
    })
  );

  register({
    key: "images",
    label: "JPEG/PNG/WebP",
    family: "Images",
    extensions: [".jpg", ".jpeg", ".png", ".webp"],
    mimeTypes: ["image/jpeg", "image/png", "image/webp"],
    strip: async ({ file, mime }) => {
      try {
        const quality =
          mime === "image/jpeg" || mime === "image/webp" ? 0.92 : undefined;
        const processedFile = await canvasReencode(file, {
          mimeHint: mime,
          quality,
        });
        return {
          processedFile,
          supported: true,
          stripped: true,
          wasModified: true,
          warnings: [
            "Image re-encoded; minor quality or metadata differences possible.",
          ],
          errors: [],
          metadataRemoved: ["EXIF/IPTC/XMP dropped by re-encode"],
          failureReason: null,
        };
      } catch (e) {
        return {
          processedFile: file,
          supported: true,
          stripped: false,
          wasModified: false,
          warnings: [],
          errors: ["reencode-failed"],
          metadataRemoved: [],
          failureReason: "Image re-encode failed",
        };
      }
    },
  });

  register({
    key: "text",
    label: "Text files",
    family: "Text",
    extensions: [
      ".txt",
      ".md",
      ".csv",
      ".log",
      ".json",
      ".xml",
      ".html",
      ".htm",
    ],
    mimeTypes: [
      "text/plain",
      "text/markdown",
      "text/csv",
      "application/json",
      "text/xml",
      "application/xml",
      "text/html",
    ],
    strip: async ({ file }) => ({
      processedFile: file,
      supported: true,
      stripped: false,
      wasModified: false,
      warnings: [],
      errors: [],
      metadataRemoved: [],
      failureReason: null,
    }),
  });

  // Update any labels that declare supported metadata types
  const updateSupportedLabels = () => {
    const label = supportedDisplay();
    document
      .querySelectorAll("[data-supported-metadata]")
      .forEach((el) => (el.textContent = label));
  };

  document.addEventListener("DOMContentLoaded", updateSupportedLabels);

  window.MetadataRegistry = {
    stripIfPossible,
    listSupported,
    supportedDisplay,
    MAX_BYTES,
  };
})();
