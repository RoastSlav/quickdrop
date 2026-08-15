#!/usr/bin/env node
/**
 * check-i18n.js
 * Three audits over the i18n surface:
 *
 *   1. Orphaned keys  — present in messages.properties, referenced nowhere.
 *   2. Enum coverage  — every constant of an enum whose labels are resolved through a
 *                       dynamic key prefix has a matching key.
 *   3. Hardcoded text — user-facing strings in templates/JS with no message key,
 *                       i.e. text that can never be translated. (--hardcoded)
 *
 * Cross-bundle consistency (missing/extra/duplicate/untranslated values) is a
 * separate concern, handled by .github/scripts/check_i18n_keys.py.
 *
 * Usage:
 *   node check-i18n.js [--verbose] [--hardcoded] [--all]
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
const ALL = process.argv.includes('--all');
const HARDCODED = ALL || process.argv.includes('--hardcoded');
const ORPHANS = ALL || !HARDCODED;

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


// ── Dynamic-prefix enum coverage ────────────────────────────────────────────
// Some labels are resolved at render time from an enum constant, e.g.
//   th:text="#{page.history.actionType.__${entry.eventType.name().toLowerCase()}__}"
// The orphan check deliberately treats such prefixes as "covered" so it does not
// flag every one of their keys as unused -- but that also means a NEW enum constant
// with no matching key is invisible to it. This closes that hole: every constant of
// the mapped enum must have a key. (Five SHORTLINK_* event types shipped without
// labels this way, rendering the raw key on the activity page.)
const ENUM_PREFIX_MAP = [
    {enumFile: 'src/main/java/org/rostislav/quickdrop/model/EventType.java', prefix: 'page.history.actionType.'},
];

function reportEnumCoverage(keys) {
    const keySet = new Set(keys);
    const gaps = [];
    for (const {enumFile, prefix} of ENUM_PREFIX_MAP) {
        let src;
        try {
            src = fs.readFileSync(path.join(__dirname, enumFile), 'utf8');
        } catch {
            continue;
        }
        const constants = [...src.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\s*\(/gm)].map(m => m[1]);
        for (const c of constants) {
            const key = prefix + c.toLowerCase();
            if (!keySet.has(key)) gaps.push({enumFile, constant: c, key});
        }
    }
    console.log('\ni18n audit — enum label coverage');
    console.log('─'.repeat(60));
    console.log(`  Missing keys : ${gaps.length}`);
    if (!gaps.length) {
        console.log('\n  Every mapped enum constant has a label key.');
    } else {
        console.log('');
        for (const g of gaps) console.log(`    ${g.constant.padEnd(24)} needs  ${g.key}`);
    }
    return gaps;
}

// ── Hardcoded user-facing text ──────────────────────────────────────────────
// A template is fine when a th:text/th:utext/th:field supplies the real content —
// the literal inside the tag is just design-time placeholder text. It is NOT fine
// when an element carries visible text and no Thymeleaf text attribute at all.

/** Tags whose text content is never shown to a user. */
const NON_VISIBLE_TAGS = new Set(['script', 'style', 'title', 'option', 'svg', 'path', 'defs', 'g']);

/** Text that is not really a label: entities, numbers, punctuation, code-ish tokens. */
function isIgnorableText(t) {
    if (!t) return true;
    // template literals that are mostly interpolation are assembled data, not copy
    if (/\$\{/.test(t)) {
        const literal = t.replace(/\$\{[^}]*\}/g, '').replace(/[\s\W]/g, '');
        if (literal.length < 3) return true;
    }
    if (t.length < 2) return true;
    if (/^[\s\W_]+$/.test(t)) return true;              // punctuation / symbols only
    if (/^(&[a-z]+;|&#\d+;|&#x[0-9a-f]+;)$/i.test(t)) return true;  // bare entity / glyph
    if (/^[\d\s.,:/%+-]+$/.test(t)) return true;        // numbers, dates, sizes
    if (/^[a-z0-9_.-]+$/.test(t) && !/\s/.test(t)) return true; // single token e.g. "utf-8"
    return false;
}

/** Blanks a matched block but keeps its newlines, so reported line numbers stay true. */
function blankPreservingLines(src, re) {
    return src.replace(re, (m) => m.replace(/[^\n]/g, ' '));
}

function scanTemplateForHardcodedText(file, src) {
    const findings = [];
    // blank out blocks whose contents are never user-visible
    let cleaned = src;
    cleaned = blankPreservingLines(cleaned, /<script\b[\s\S]*?<\/script>/gi);
    cleaned = blankPreservingLines(cleaned, /<style\b[\s\S]*?<\/style>/gi);
    cleaned = blankPreservingLines(cleaned, /<!--[\s\S]*?-->/g);

    // Walk tags and text runs, keeping a stack of open elements. A text run belongs to
    // the element on top of the stack -- which is how mixed content like
    //   <button><span aria-hidden="true">&#9656;</span> Technical info</button>
    // gets caught: "Technical info" belongs to <button>, not to the <span> beside it.
    const VOID = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
        'link', 'meta', 'param', 'source', 'track', 'wbr']);
    const tagRe = /<\/?([a-zA-Z][\w:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
    const stack = [];
    let last = 0;
    let m;

    const emitText = (raw, index) => {
        const owner = stack[stack.length - 1];
        if (!owner) return;
        if (NON_VISIBLE_TAGS.has(owner.tag)) return;
        if (suppliesText(owner.attrs)) return;
        const text = raw.replace(/\s+/g, ' ').trim();
        if (isIgnorableText(text)) return;
        if (/\[\[.*\]\]|\[\(.*\)\]/.test(text)) return;   // Thymeleaf inlining is a binding
        const line = cleaned.slice(0, index).split('\n').length;
        findings.push({file, line, tag: owner.tag, text: text.slice(0, 70)});
    };

    while ((m = tagRe.exec(cleaned)) !== null) {
        if (m.index > last) emitText(cleaned.slice(last, m.index), last);
        const raw = m[0];
        const tag = m[1].toLowerCase();
        const attrs = m[2] || '';
        if (raw.startsWith('</')) {
            for (let i = stack.length - 1; i >= 0; i--) {
                if (stack[i].tag === tag) { stack.length = i; break; }
            }
        } else if (!VOID.has(tag) && !raw.endsWith('/>')) {
            stack.push({tag, attrs});
        }
        last = tagRe.lastIndex;
    }
    if (last < cleaned.length) emitText(cleaned.slice(last), last);
    return findings;
}

/** True when Thymeleaf (or a language switcher) legitimately owns this element's text. */
function suppliesText(attrs) {
    if (/\bth:(text|utext|field|each|replace|include|insert)\s*=/.test(attrs)) return true;
    // A language switcher shows each language in its own language ("Deutsch" stays
    // "Deutsch" in every locale), so those literals are correct, not missing keys.
    if (/data-lang\s*=/.test(attrs)) return true;
    if (/class\s*=\s*"[^"]*\b(lang-option|lang-code|mobile-lang-chip)\b/.test(attrs)) return true;
    return false;
}

/**
 * JS strings that reach the user: toast/notify/confirmAction copy and direct
 * textContent assignments. Anything read out of window.i18n / QD_*_I18N is fine.
 */
function scanScriptForHardcodedText(file, src) {
    const findings = [];
    // Comments hold usage examples (JSDoc "Usage: toast('Message')"), not shipped copy.
    // Blanked rather than removed so line numbers still point at the real source.
    const lines = blankPreservingLines(src, /\/\*[\s\S]*?\*\//g)
        .replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1')
        .split(/\r?\n/);
    const callRe = /\b(?:window\.)?(toast|notify|confirmAction)\s*\(\s*(['"`])((?:\\.|(?!\2).)*)\2/;
    const assignRe = /\.(?:textContent|innerText|placeholder|title)\s*=\s*(['"`])((?:\\.|(?!\1).)*)\1/;
    lines.forEach((line, i) => {
        if (/i18n|I18N|getI18nStr/.test(line)) return;      // sourced from a bundle
        let m = callRe.exec(line);
        if (m && !isIgnorableText(m[3].trim())) {
            findings.push({file, line: i + 1, tag: m[1] + '()', text: m[3].slice(0, 70)});
            return;
        }
        m = assignRe.exec(line);
        if (m && !isIgnorableText(m[2].trim())) {
            findings.push({file, line: i + 1, tag: 'textContent', text: m[2].slice(0, 70)});
        }
    });
    return findings;
}

function reportHardcoded(files) {
    const findings = [];
    for (const f of files) {
        const ext = path.extname(f);
        let src;
        try {
            src = fs.readFileSync(f, 'utf8');
        } catch {
            continue;
        }
        const rel = path.relative(__dirname, f).replace(/\\/g, '/');
        if (ext === '.html') findings.push(...scanTemplateForHardcodedText(rel, src));
        else if (ext === '.js') findings.push(...scanScriptForHardcodedText(rel, src));
    }

    console.log('\ni18n audit — hardcoded user-facing text');
    console.log('─'.repeat(60));
    console.log(`  Findings     : ${findings.length}`);
    if (!findings.length) {
        console.log('\n  No untranslatable user-facing strings found.');
        return findings;
    }
    const byFile = new Map();
    for (const f of findings) {
        if (!byFile.has(f.file)) byFile.set(f.file, []);
        byFile.get(f.file).push(f);
    }
    for (const [file, list] of [...byFile].sort((a, b) => b[1].length - a[1].length)) {
        console.log(`\n  ${file} (${list.length})`);
        for (const f of list) console.log(`    ${String(f.line).padStart(5)}  <${f.tag}>  ${f.text}`);
    }
    return findings;
}

// ── Main ────────────────────────────────────────────────────────────────────
function main() {
    if (!fs.existsSync(MESSAGES_FILE)) {
        console.error(`ERROR: messages.properties not found at:\n  ${MESSAGES_FILE}`);
        process.exit(1);
    }

    const keys = parseKeys(MESSAGES_FILE);
    const files = collectFiles(SEARCH_DIRS);

    if (!ORPHANS) {
        const only = reportHardcoded(files);
        if (only.length > 0) process.exitCode = 1;
        return;
    }

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

    const gaps = reportEnumCoverage(keys);
    if (gaps.length > 0) process.exitCode = 1;

    if (HARDCODED) {
        const hardcoded = reportHardcoded(files);
        if (hardcoded.length > 0) process.exitCode = 1;
    }
}

main();
