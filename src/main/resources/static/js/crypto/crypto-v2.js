// AES-GCM v2 client crypto helpers (browser/WebCrypto)
const MAGIC = new Uint8Array([0x51, 0x44, 0x45, 0x4e, 0x43]); // "QDENC"
const VERSION = 0x02;
const KDF = { PBKDF2: 1 };
const DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MB
const DEFAULT_ITERATIONS = 300000;
const DEFAULT_SALT_LEN = 32;
const GCM_NONCE_LEN = 12;
const GCM_TAG_LEN = 16;

function concatUint8(...parts) {
  const total = parts.reduce((acc, p) => acc + p.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

function u32be(num) {
  const buf = new ArrayBuffer(4);
  new DataView(buf).setUint32(0, num >>> 0, false);
  return new Uint8Array(buf);
}

function u64be(num) {
  const buf = new ArrayBuffer(8);
  const view = new DataView(buf);
  const hi = Math.floor(num / 2 ** 32);
  const lo = num >>> 0;
  view.setUint32(0, hi, false);
  view.setUint32(4, lo, false);
  return new Uint8Array(buf);
}

function encodeAAD(fileId, chunkIndex, totalChunks) {
  const encoder = new TextEncoder();
  const fileIdBytes = encoder.encode(fileId || "");
  return concatUint8(
    MAGIC,
    new Uint8Array([VERSION]),
    fileIdBytes,
    u32be(chunkIndex >>> 0),
    u32be(totalChunks >>> 0)
  );
}

async function deriveKey(password, salt, iterations) {
  const encoder = new TextEncoder();
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      salt,
      iterations,
      hash: "SHA-256",
    },
    keyMaterial,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

function buildHeader({
  salt,
  iterations,
  totalChunks,
  plaintextSize,
  kdfId = KDF.PBKDF2,
}) {
  const header = concatUint8(
    MAGIC,
    new Uint8Array([VERSION]),
    new Uint8Array([0x00]), // flags reserved
    new Uint8Array([kdfId]),
    new Uint8Array([salt.length]),
    salt,
    u32be(iterations >>> 0),
    u32be(totalChunks >>> 0),
    u64be(plaintextSize)
  );
  return header;
}

export async function detectFormat(blob) {
  const head = new Uint8Array(await blob.slice(0, 6).arrayBuffer());
  const hasMagic = MAGIC.every((b, i) => head[i] === b);
  if (!hasMagic) return { version: 1, format: "legacy", hasMagic: false };
  const version = head[5];
  return { version, format: version === VERSION ? "v2" : "unknown", hasMagic };
}

export async function readHeader(blob) {
  const first = new Uint8Array(await blob.slice(0, 5 + 1 + 1 + 1 + DEFAULT_SALT_LEN + 4 + 4 + 8).arrayBuffer());
  if (!MAGIC.every((b, i) => first[i] === b)) throw new Error("Invalid magic");
  const version = first[5];
  if (version !== VERSION) throw new Error(`Unsupported version ${version}`);
  let offset = 6; // after magic + version
  const flags = first[offset++];
  const kdfId = first[offset++];
  const saltLen = first[offset++];
  const needed = 6 + 1 + 1 + 1 + saltLen + 4 + 4 + 8;
  const headerBuf = new Uint8Array(await blob.slice(0, needed).arrayBuffer());
  offset = 6 + 1 + 1; // after magic+ver+flags+kdf
  offset++; // saltLen already read
  const salt = headerBuf.slice(offset, offset + saltLen);
  offset += saltLen;
  const view = new DataView(headerBuf.buffer);
  const iterations = view.getUint32(offset, false);
  offset += 4;
  const totalChunks = view.getUint32(offset, false);
  offset += 4;
  const hi = view.getUint32(offset, false);
  const lo = view.getUint32(offset + 4, false);
  const plaintextSize = hi * 2 ** 32 + lo;
  return {
    version,
    flags,
    kdfId,
    salt,
    iterations,
    totalChunks,
    plaintextSize,
    headerLength: needed,
  };
}

export async function encryptStreamOrChunks(
  blob,
  password,
  { chunkSize = DEFAULT_CHUNK_SIZE, fileId = "", iterations = DEFAULT_ITERATIONS, salt: saltOpt } = {}
) {
  const salt = saltOpt || crypto.getRandomValues(new Uint8Array(DEFAULT_SALT_LEN));
  const totalChunks = Math.max(1, Math.ceil(blob.size / chunkSize));
  const key = await deriveKey(password, salt, iterations);
  const header = buildHeader({ salt, iterations, totalChunks, plaintextSize: blob.size });

  async function* generator() {
    yield { type: "header", index: -1, data: header };
    for (let i = 0; i < totalChunks; i++) {
      const start = i * chunkSize;
      const end = Math.min(start + chunkSize, blob.size);
      const plain = new Uint8Array(await blob.slice(start, end).arrayBuffer());
      const nonce = crypto.getRandomValues(new Uint8Array(GCM_NONCE_LEN));
      const aad = encodeAAD(fileId, i, totalChunks);
      const cipherBuf = await crypto.subtle.encrypt(
        { name: "AES-GCM", iv: nonce, additionalData: aad, tagLength: GCM_TAG_LEN * 8 },
        key,
        plain
      );
      const cipherBytes = new Uint8Array(cipherBuf);
      const chunkRecord = concatUint8(
        u32be(i >>> 0),
        new Uint8Array([nonce.length]),
        nonce,
        u32be(cipherBytes.length),
        cipherBytes
      );
      yield { type: "chunk", index: i, data: chunkRecord };
    }
  }

  return { header, totalChunks, plaintextSize: blob.size, salt, iterations, kdfId: KDF.PBKDF2, stream: generator() };
}

export async function decryptStreamOrChunks(
  blob,
  password,
  { fileId = "" } = {}
) {
  const header = await readHeader(blob);
  if (header.kdfId !== KDF.PBKDF2) throw new Error("Unsupported KDF");
  const key = await deriveKey(password, header.salt, header.iterations);

  async function* generator() {
    let offset = header.headerLength;
    const total = header.totalChunks;
    const fileSize = blob.size;
    for (let expected = 0; expected < total && offset < fileSize; expected++) {
      const base = new Uint8Array(await blob.slice(offset, offset + 4 + 1 + GCM_NONCE_LEN + 4).arrayBuffer());
      const view = new DataView(base.buffer);
      const chunkIndex = view.getUint32(0, false);
      const nonceLen = base[4];
      const nonceBytes = new Uint8Array(await blob.slice(offset + 5, offset + 5 + nonceLen).arrayBuffer());
      const lenPos = offset + 5 + nonceLen;
      const lenBuf = new Uint8Array(await blob.slice(lenPos, lenPos + 4).arrayBuffer());
      const cipherLen = new DataView(lenBuf.buffer).getUint32(0, false);
      const cipherStart = lenPos + 4;
      const cipherEnd = cipherStart + cipherLen;
      const cipherBytes = new Uint8Array(await blob.slice(cipherStart, cipherEnd).arrayBuffer());
      offset = cipherEnd;

      if (chunkIndex !== expected) throw new Error("Chunk order/auth mismatch");
      const aad = encodeAAD(fileId, chunkIndex, total);
      const plainBuf = await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: nonceBytes, additionalData: aad, tagLength: GCM_TAG_LEN * 8 },
        key,
        cipherBytes
      );
      yield { type: "chunk", index: chunkIndex, data: new Uint8Array(plainBuf) };
    }
  }

  return { header, stream: generator() };
}

export function getOverheadEstimate(plaintextSize, { chunkSize = DEFAULT_CHUNK_SIZE, saltLen = DEFAULT_SALT_LEN } = {}) {
  const totalChunks = Math.max(1, Math.ceil(plaintextSize / chunkSize));
  const headerBytes = MAGIC.length + 1 + 1 + 1 + 1 + saltLen + 4 + 4 + 8; // magic+ver+flags+kdf+saltLen+salt+iter+totalChunks+size
  const perChunkOverhead = 4 + 1 + GCM_NONCE_LEN + 4 + 0; // index + nonceLen + nonce + cipherLen field (tag is in cipher)
  const tagOverhead = GCM_TAG_LEN * totalChunks;
  const overhead = headerBytes + perChunkOverhead * totalChunks + tagOverhead;
  return { overhead, headerBytes, perChunkOverhead, totalChunks };
}
