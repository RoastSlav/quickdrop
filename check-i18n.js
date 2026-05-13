#!/usr/bin/env node
/**
 * check-i18n.js
 * Scans messages.properties and reports keys that are not referenced anywhere
 * in templates, JS source, or Java source.
 *
 * Usage:
 *   node check-i18n.js [--verbose]
 */

const fs = require('fs');
const path = require('path');

// ── Config ─────────────────────────────────────────────────────────────────
const MESSAGES_FILE = path.join(__dirname, 'src/main/resources/messages.properties');

const SEARCH_DIRS = [
    'src/main/resources/templates',
    'src/main/resources/static/js',
    'src/main/java',
];

const SEARCH_EXTS = new Set(['.html', '.js', '.java']);

const VERBOSE = process.argv.includes('--verbose');

// ── Parse messages.properties ───────────────────────────────────────────────
function parseKeys(file) {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    const keys = [];
    for (const line of lines) {
        const t = line.trim();
        if (!t || t.startsWith('#') || t.startsWith('!')) continue;
        const eq = t.indexOf('=');
        if (eq === -1) continue;
        const key = t.slice(0, eq).trimEnd();
        if (key) keys.push(key);
    }
    return keys;
}

// ── Collect source files ────────────────────────────────────────────────────
function collectFiles(dirs) {
    const result = [];

    function walk(dir) {
        let entries;
        try {
            entries = fs.readdirSync(dir, {withFileTypes: true});
        } catch {
            return;
        }
        for (const e of entries) {
            const full = path.join(dir, e.name);
            if (e.isDirectory()) {
                walk(full);
            } else if (SEARCH_EXTS.has(path.extname(e.name))) {
                result.push(full);
            }
        }
    }

    for (const d of dirs) walk(path.join(__dirname, d));
    return result;
}

// ── Build corpus ────────────────────────────────────────────────────────────
function buildCorpus(files) {
    const parts = [];
    for (const f of files) {
        try {
            parts.push(fs.readFileSync(f, 'utf8'));
        } catch { /* skip unreadable */
        }
    }
    return parts.join('\n');
}

// ── Detect dynamic-prefix patterns like #{|some.prefix.${var}|} ────────────
// Returns a set of key prefixes that are used dynamically.
function detectDynamicPrefixes(corpus) {
    const prefixes = new Set();
    // Thymeleaf literal substitution: #{|prefix.${...}|}
    const re1 = /\#\{\|([^$|{]+)\$\{/g;
    let m;
    while ((m = re1.exec(corpus)) !== null) prefixes.add(m[1]);
    return prefixes;
}

// ── Main ────────────────────────────────────────────────────────────────────
function main() {
    if (!fs.existsSync(MESSAGES_FILE)) {
        console.error(`ERROR: messages.properties not found at:\n  ${MESSAGES_FILE}`);
        process.exit(1);
    }

    const keys = parseKeys(MESSAGES_FILE);
    const files = collectFiles(SEARCH_DIRS);
    const corpus = buildCorpus(files);
    const dynPfx = detectDynamicPrefixes(corpus);

    const orphaned = [];
    const dynamicCovered = [];
    const used = [];

    for (const key of keys) {
        if (corpus.includes(key)) {
            used.push(key);
            continue;
        }

        // Check if a dynamic prefix covers this key
        // e.g. prefix "page.history.actionType." covers "page.history.actionType.download"
        const coveredBy = [...dynPfx].find(p => key.startsWith(p));
        if (coveredBy) {
            dynamicCovered.push({key, coveredBy});
            continue;
        }

        orphaned.push(key);
    }

    // ── Report ────────────────────────────────────────────────────────────────
    const w = (s) => process.stdout.write(s + '\n');

    w('');
    w(`i18n audit — messages.properties`);
    w(`${'─'.repeat(60)}`);
    w(`  Keys checked : ${keys.length}`);
    w(`  Source files : ${files.length}`);
    w(`  Used         : ${used.length}`);
    w(`  Dynamic pfx  : ${dynamicCovered.length}`);
    w(`  Orphaned     : ${orphaned.length}`);
    w('');

    if (dynamicCovered.length > 0 && VERBOSE) {
        w(`Dynamic-prefix covered keys (${dynamicCovered.length}):`);
        for (const {key, coveredBy} of dynamicCovered) {
            w(`  ~ ${key}  (prefix: "${coveredBy}")`);
        }
        w('');
    }

    if (VERBOSE && used.length > 0) {
        w(`Used keys (${used.length}):`);
        for (const k of used) w(`  ✓ ${k}`);
        w('');
    }

    if (orphaned.length === 0) {
        w('✓ No orphaned keys found.');
    } else {
        w(`Orphaned keys — not found in any template, JS, or Java file (${orphaned.length}):`);
        for (const k of orphaned) w(`  ✗ ${k}`);
        w('');
        w('Tip: Run with --verbose to also print used and dynamic-covered keys.');
        process.exitCode = 1;
    }

    w('');
}

main();
