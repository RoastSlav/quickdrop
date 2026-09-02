import test from "node:test";
import assert from "node:assert/strict";

import {formatBytes} from "../../../main/resources/static/js/upload/format.js";

test("formatBytes: matches the server's two-decimal, 1024-step formatting", () => {
    assert.equal(formatBytes(0), "0.00 B");
    assert.equal(formatBytes(512), "512.00 B");
    assert.equal(formatBytes(1024), "1.00 KB");
    assert.equal(formatBytes(3877), "3.79 KB");
    assert.equal(formatBytes(1024 * 1024), "1.00 MB");
    assert.equal(formatBytes(1024 * 1024 * 1024), "1.00 GB");
    assert.equal(formatBytes(1024 ** 4), "1.00 TB");
});

test("formatBytes: stops at the largest unit rather than inventing one", () => {
    assert.equal(formatBytes(1024 ** 5), "1024.00 TB");
});

test("formatBytes: treats missing or nonsensical sizes as zero", () => {
    assert.equal(formatBytes(undefined), "0.00 B");
    assert.equal(formatBytes(null), "0.00 B");
    assert.equal(formatBytes(-1), "0.00 B");
    assert.equal(formatBytes(NaN), "0.00 B");
});
