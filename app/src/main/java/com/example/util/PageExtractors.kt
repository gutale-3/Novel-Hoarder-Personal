package com.example.util

/**
 * Shared page-extraction scripts.
 *
 * Every script returns a plain JS object (never JSON.stringify) — JsResultParser handles either
 * shape, but returning objects keeps the payload one level deep.
 */
object PageExtractors {

    /**
     * Readiness probe for a table-of-contents page.
     *
     * Counts CHAPTER-LIKE links only, and reports ready only once the count stops changing between
     * two consecutive polls. A probe that just checks `links.length > 5` is satisfied immediately by
     * any site's nav bar — so extraction runs before the chapter list has loaded and the download
     * falls back to a single chapter.
     */
    const val TOC_READY_JS = """
        (() => {
            let n = 0;
            Array.from(document.querySelectorAll('a[href]')).forEach(a => {
                try {
                    const u = new URL(a.getAttribute('href'), location.href);
                    if (u.origin !== location.origin) return;
                    const t = (a.innerText || a.textContent || '').trim();
                    const looksLikeChapter =
                        /chapter\s*\d+|ch\.?\s*\d+|episode\s*\d+|\bvol(ume)?\s*\d+/i.test(t) ||
                        /\/chapter|\/chap|\/ch-|\/c\d+|\/episode/i.test(u.pathname);
                    if (looksLikeChapter) n++;
                } catch (e) {}
            });
            const previous = (typeof window.__nhTocCount === 'number') ? window.__nhTocCount : -1;
            window.__nhTocCount = n;
            return { ready: n > 0 && n === previous, count: n };
        })()
    """

    /**
     * Extracts chapter links together with their visible text.
     *
     * The text is needed because the same chapter is often linked from several places under
     * different URLs ("Start reading", "Latest chapter", the list entry itself). Only the label
     * reveals that they are the same chapter.
     */
    const val TOC_EXTRACT_JS = """
        (() => {
            const results = [];
            Array.from(document.querySelectorAll('a[href]')).forEach(a => {
                try {
                    const u = new URL(a.getAttribute('href'), location.href);
                    if (u.origin !== location.origin) return;
                    if (u.href === location.href) return;

                    const text = (a.innerText || a.textContent || '').trim();
                    const byText = /chapter\s*\d+|ch\.?\s*\d+|episode\s*\d+|\bvol(ume)?\s*\d+/i.test(text);
                    const byHref = /\/chapter|\/chap|\/ch-|\/c\d+|\/episode/i.test(u.pathname);

                    if (byText || byHref) {
                        results.push({ href: u.href, text: text.slice(0, 160) });
                    }
                } catch (e) {}
            });
            return results.slice(0, 5000);
        })()
    """

    /** Containers that typically hold a single chapter's body, most specific first. */
    const val CHAPTER_CONTAINER_SELECTOR =
        "article, main, .chapter-content, #chapter-content, .entry-content, .content, #content, .reading-content"

    /**
     * Records a fingerprint of the chapter container *before* navigating away.
     *
     * Single-page-app readers swap chapter text without creating a new document, so the readiness
     * probe cannot tell "loaded" from "still showing the previous chapter" without this.
     */
    const val MARK_PREVIOUS_CONTENT_JS = """
        (() => {
            try {
                const el = document.querySelector('$CHAPTER_CONTAINER_SELECTOR') || document.body;
                const t = (el.textContent || '').replace(/\s+/g, ' ').trim();
                window.__nhPrevFp = t.length + ':' + t.slice(0, 200);
                window.__nhLastFp = null;
            } catch (e) {}
            return true;
        })()
    """

    fun chapterReadyJs(expectedNumber: Int?): String {
        val expected = expectedNumber?.toString() ?: "null"
        return """
            (() => {
                const el = document.querySelector('$CHAPTER_CONTAINER_SELECTOR') || document.body;
                // innerText, not textContent: this element is live in the document, so innerText
                // reflects layout and yields real lines. textContent returns one unbroken run,
                // which made the heading scan below find nothing and report a match every time.
                const raw = (el.innerText || el.textContent || '');
                const t = raw.replace(/\s+/g, ' ').trim();
                if (t.length < 200) return { ready: false, matched: false, length: t.length };

                const fp = t.length + ':' + t.slice(0, 200);
                const prev = window.__nhPrevFp;
                const last = window.__nhLastFp;
                window.__nhLastFp = fp;

                const changed = !prev || fp !== prev;
                const stable = last === fp;

                // Read the chapter number the page states, from the first heading near the top.
                let pageNumber = null;
                const lines = raw.split('\n').map(s => s.trim()).filter(s => s.length > 0).slice(0, 8);
                for (const line of lines) {
                    if (line.length > 120) continue;
                    if (/^ch\.?\s*\d+\s*\/\s*\d+/i.test(line)) continue;  // "Ch. 3 / 70" widget
                    const m = line.match(/^(?:chapter|ch\.?|episode|ep\.?)\s*(\d+)/i);
                    if (m) { pageNumber = parseInt(m[1], 10); break; }
                }

                const expected = $expected;
                const matched = expected === null || pageNumber === null || pageNumber === expected;

                return { ready: changed && stable, matched: matched,
                         pageNumber: pageNumber, length: t.length };
            })()
        """.trimIndent()
    }

    /**
     * Extracts one chapter's title and body from whatever page is currently loaded.
     *
     * Selecting 'p, div' together returns wrappers AND their children — duplicating every
     * paragraph once per level of nesting. Read the tree once instead.
     */
    const val CHAPTER_CONTENT_JS = """
        (() => {
            let title = "";
            const heading = document.querySelector('h1, .chapter-title, .entry-title, #chapter-title');
            if (heading) title = (heading.innerText || '').trim();
            if (!title) title = (document.title || '').split('|')[0].trim();

            let best = document.querySelector('$CHAPTER_CONTAINER_SELECTOR');

            if (!best) {
                let maxP = 0;
                document.querySelectorAll('div, section').forEach(el => {
                    const c = el.querySelectorAll('p').length;
                    if (c > maxP) { maxP = c; best = el; }
                });
            }

            let text = "";
            if (best) {
                const clone = best.cloneNode(true);
                clone.querySelectorAll(
                    'script, style, noscript, nav, header, footer, iframe, form, button, select, textarea, ' +
                    '.ad, .ads, .advertisement, .comments, #comments, .share, .social, ' +
                    '.glossary, #glossary, .ner, #ner, .replacement, .translation-tools, ' +
                    '.translator-panel, .tool-panel, .ai-tools, .chapter-tools, .toolbar'
                ).forEach(el => el.remove());

                // A cloned node is detached from the document, so it has no layout and innerText
                // is unavailable — every read silently falls through to textContent, which drops
                // all line breaks and collapses the chapter into one paragraph. Turn the breaks
                // into real text nodes first, then read textContent once over the whole tree.
                clone.querySelectorAll('br').forEach(br => {
                    if (br.parentNode) br.parentNode.replaceChild(document.createTextNode('\n'), br);
                });
                clone.querySelectorAll(
                    'p, div, section, article, li, tr, figure, blockquote, h1, h2, h3, h4, h5, h6'
                ).forEach(el => { el.appendChild(document.createTextNode('\n\n')); });

                text = (clone.textContent || '')
                    .replace(/ /g, ' ')
                    .split('\n').map(s => s.trim()).filter(s => s.length > 0)
                    .join('\n\n');
            } else {
                text = document.body
                    ? ((document.body.innerText || document.body.textContent || '').trim())
                    : "";
            }

            return { title: title || "Chapter", content: text };
        })()
    """

    /** Extracts book metadata from a novel landing / table-of-contents page. */
    const val BOOK_INFO_JS = """
        (() => {
            const q = (s) => {
                try {
                    const e = document.querySelector(s);
                    return e ? (e.innerText || e.textContent || '').trim() : "";
                } catch (err) { return ""; }
            };
            const attr = (s, a) => {
                try {
                    const e = document.querySelector(s);
                    return e ? (e.getAttribute(a) || "") : "";
                } catch (err) { return ""; }
            };

            let title = attr('meta[property="og:title"]', 'content') ||
                        q('h1') || q('.book-title') || q('.novel-title') || q('.entry-title') ||
                        document.title || "";
            title = title.split('|')[0].split(' - ')[0].trim();

            let author = attr('meta[name="author"]', 'content') ||
                         attr('meta[property="article:author"]', 'content') ||
                         q('.author-content') || q('.author') || "";
            if (!author) {
                document.querySelectorAll('p, span, div, li').forEach(e => {
                    const t = (e.innerText || '').trim();
                    if (!author && /^author\s*[:：]/i.test(t)) {
                        author = t.replace(/^author\s*[:：]\s*/i, '').split('\n')[0].trim();
                    }
                });
            }

            const synopsis = attr('meta[property="og:description"]', 'content') ||
                             attr('meta[name="description"]', 'content') ||
                             q('.book-description') || q('.synopsis') || q('#synopsis') ||
                             q('.description') || q('.summary') || "";

            let cover = attr('meta[property="og:image"]', 'content') ||
                        attr('img.book-cover', 'src') || attr('.cover img', 'src') ||
                        attr('.summary_image img', 'src') || attr('img[src*="cover"]', 'src') || "";
            if (cover && cover.indexOf('http') !== 0) {
                try { cover = new URL(cover, location.href).href; } catch (e) { cover = ""; }
            }

            return { ready: title.length > 0, title: title,
                     author: author || "Unknown Author",
                     synopsis: synopsis || "", cover: cover };
        })()
    """
}
