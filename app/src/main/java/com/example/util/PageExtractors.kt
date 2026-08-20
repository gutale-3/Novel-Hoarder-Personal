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
            const seen = new Set();
            
            // Check <a> links
            Array.from(document.querySelectorAll('a[href]')).forEach(a => {
                try {
                    const u = new URL(a.getAttribute('href'), location.href);
                    if (u.origin !== location.origin) return;
                    const t = (a.innerText || a.textContent || '').trim();
                    const looksLikeChapter =
                        /chapter\s*\d+|ch\.?\s*\d+|episode\s*\d+|\bvol(ume)?\s*\d+|prologue|epilogue|act\s*\d+/i.test(t) ||
                        /\/chapter|\/chap|\/ch-|\/c\d+|\/episode|\/read\/\d+/i.test(u.pathname);
                    if (looksLikeChapter) {
                        const norm = u.origin + u.pathname;
                        if (!seen.has(norm)) {
                            seen.add(norm);
                            n++;
                        }
                    }
                } catch (e) {}
            });
            
            // Also check <option> dropdown items if present
            Array.from(document.querySelectorAll('select option')).forEach(opt => {
                const val = opt.value || '';
                const t = (opt.innerText || opt.textContent || '').trim();
                if (/chapter\s*\d+|ch\.?\s*\d+|episode\s*\d+/i.test(t) || /\/chapter|\/chap|\/ch-/i.test(val)) {
                    n++;
                }
            });
            
            const previous = (typeof window.__nhTocCount === 'number') ? window.__nhTocCount : -1;
            window.__nhTocCount = n;
            return { ready: n > 0 && n === previous, count: n };
        })()
    """

    /**
     * Extracts chapter links together with their visible text.
     *
     * Scans <a> tags, <select> dropdowns, and data-url attributes for chapter targets.
     */
    const val TOC_EXTRACT_JS = """
        (() => {
            const results = [];
            const seenUrls = new Set();

            const addLink = (href, text) => {
                try {
                    if (!href) return;
                    const u = new URL(href, location.href);
                    if (u.origin !== location.origin) return;
                    const cleanHref = u.origin + u.pathname + (u.search ? u.search : '');
                    if (cleanHref === location.href && !u.search) return;
                    if (seenUrls.has(cleanHref)) return;
                    seenUrls.add(cleanHref);
                    results.push({ href: cleanHref, text: (text || '').trim().slice(0, 160) });
                } catch (e) {}
            };

            // 1. Standard chapter link scan
            Array.from(document.querySelectorAll('a[href]')).forEach(a => {
                try {
                    const h = a.getAttribute('href');
                    const text = (a.innerText || a.textContent || '').trim();
                    const u = new URL(h, location.href);
                    
                    const byText = /chapter\s*\d+|ch\.?\s*\d+|episode\s*\d+|\bvol(ume)?\s*\d+|prologue|epilogue|act\s*\d+/i.test(text);
                    const byHref = /\/chapter|\/chap|\/ch-|\/c\d+|\/episode|\/read\/\d+|\/novel\/[^\/]+\/\d+/i.test(u.pathname);
                    const byClass = /(chapter|volume|toc|entry|episode)/i.test(a.className || '') ||
                                    /(chapter|volume|toc|entry|episode)/i.test(a.parentElement?.className || '');

                    if (byText || byHref || (byClass && /\d+/.test(u.pathname))) {
                        addLink(h, text);
                    }
                } catch (e) {}
            });

            // 2. Scan chapter dropdowns (<select>)
            Array.from(document.querySelectorAll('select option')).forEach(opt => {
                try {
                    const val = opt.value;
                    const text = opt.innerText || opt.textContent || '';
                    if (val && (/^https?:\/\//i.test(val) || /^\//.test(val))) {
                        addLink(val, text);
                    }
                } catch (e) {}
            });

            // 3. Scan elements with data-url or data-href
            Array.from(document.querySelectorAll('[data-url], [data-href]')).forEach(el => {
                try {
                    const h = el.getAttribute('data-url') || el.getAttribute('data-href');
                    const text = el.innerText || el.textContent || '';
                    if (h) addLink(h, text);
                } catch (e) {}
            });

            return results.slice(0, 8000);
        })()
    """

    /** Containers that typically hold a single chapter's body, most specific first. */
    const val CHAPTER_CONTAINER_SELECTOR =
        "article, main, .chapter-content, #chapter-content, .entry-content, .content, #content, .reading-content, .text-content, .read-content, #chapter-article"

    /**
     * Records a fingerprint of the chapter container *before* navigating away.
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
                    if (/^ch\.?\s*\d+\s*\/\s*\d+/i.test(line)) continue;
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
     * Advanced Readability & Text-Density Chapter Content Extraction Script.
     *
     * Evaluates DOM text density, strips invisible anti-scrape traps and ad elements,
     * rejects link-heavy containers, and formats narrative paragraphs cleanly with double line breaks.
     */
    const val CHAPTER_CONTENT_JS = """
        (() => {
            // Helper: Detect if an element is hidden via CSS / anti-scrape injection
            const isElementHidden = (el) => {
                try {
                    if (!el) return false;
                    const style = window.getComputedStyle(el);
                    if (!style) return false;
                    if (style.display === 'none' ||
                        style.visibility === 'hidden' ||
                        style.opacity === '0' ||
                        style.fontSize === '0px' ||
                        style.userSelect === 'none') {
                        return true;
                    }
                    if (style.position === 'absolute') {
                        const left = parseInt(style.left, 10);
                        const top = parseInt(style.top, 10);
                        if (left < -1000 || top < -1000) return true;
                    }
                    return false;
                } catch (e) {
                    return false;
                }
            };

            // 1. Extract chapter title
            let title = "";
            const heading = document.querySelector('h1, h2, .chapter-title, .entry-title, #chapter-title, .reader-header h1, .post-title');
            if (heading) title = (heading.innerText || heading.textContent || '').trim();
            if (!title) title = (document.title || '').split('|')[0].split(' - ')[0].trim();

            // 2. Purge invisible trap nodes from live DOM before clone
            const hiddenNodes = [];
            try {
                document.querySelectorAll('span, div, p, i, b, font').forEach(el => {
                    if (isElementHidden(el)) {
                        hiddenNodes.push({ el: el, parent: el.parentNode, sibling: el.nextSibling });
                        el.remove();
                    }
                });
            } catch (e) {}

            // 3. Find the best narrative content container via Text-Density Scoring
            const candidates = Array.from(document.querySelectorAll(
                'article, main, .chapter-content, #chapter-content, .entry-content, .content, #content, .reading-content, .text-content, .read-content, #chapter-article, div, section'
            ));

            let bestContainer = null;
            let highestScore = -1;

            candidates.forEach(el => {
                try {
                    // Quick reject for navigation/headers/footers/toolbars
                    const tag = el.tagName.toLowerCase();
                    if (tag === 'nav' || tag === 'header' || tag === 'footer' || tag === 'aside') return;
                    
                    const cls = (el.className || '').toLowerCase();
                    const id = (el.id || '').toLowerCase();
                    if (cls.includes('nav') || cls.includes('menu') || cls.includes('sidebar') ||
                        cls.includes('footer') || cls.includes('comment') || cls.includes('modal') ||
                        cls.includes('popup') || id.includes('nav') || id.includes('sidebar')) {
                        return;
                    }

                    const rawText = el.innerText || el.textContent || '';
                    const textLen = rawText.length;
                    if (textLen < 150) return;

                    // Calculate link density (ratio of text inside <a> tags vs total text)
                    let linkLen = 0;
                    el.querySelectorAll('a').forEach(a => {
                        linkLen += (a.innerText || a.textContent || '').length;
                    });
                    const linkDensity = linkLen / Math.max(textLen, 1);
                    if (linkDensity > 0.28) return; // Reject navigation & lists

                    const pCount = el.querySelectorAll('p').length;
                    const commaCount = (rawText.match(/[,，;；]/g) || []).length;
                    const periodCount = (rawText.match(/[.!?。！？]/g) || []).length;

                    // Density score formula
                    let score = (textLen * 0.5) + (pCount * 60) + (commaCount * 10) + (periodCount * 15);
                    score *= (1 - linkDensity);

                    // Bonus for chapter-specific class / id
                    if (cls.includes('chapter') || cls.includes('content') || cls.includes('read') ||
                        cls.includes('entry') || cls.includes('story') || id.includes('chapter') || id.includes('content')) {
                        score += 300;
                    }

                    if (score > highestScore) {
                        highestScore = score;
                        bestContainer = el;
                    }
                } catch (e) {}
            });

            // 4. Clone best container and clean boilerplate
            let resultText = "";
            const targetNode = bestContainer || document.querySelector('$CHAPTER_CONTAINER_SELECTOR') || document.body;

            if (targetNode) {
                const clone = targetNode.cloneNode(true);
                
                // Remove junk tags and ad containers
                clone.querySelectorAll(
                    'script, style, noscript, nav, header, footer, iframe, form, button, select, textarea, svg, canvas, audio, video, dialog, aside, ' +
                    '.ad, .ads, .advertisement, [id*="google_ads"], [class*="google-ads"], [class*="ad-container"], ' +
                    '.comments, #comments, .comment-section, .share, .social, .social-share, ' +
                    '.chapter-nav, .nav-buttons, .prev-next, .novel-buttons, .report-box, .watermark, ' +
                    '.glossary, #glossary, .ner, #ner, .replacement, .translation-tools, ' +
                    '.translator-panel, .tool-panel, .ai-tools, .chapter-tools, .toolbar, [aria-hidden="true"]'
                ).forEach(junk => junk.remove());

                // Convert <br> to newline
                clone.querySelectorAll('br').forEach(br => {
                    if (br.parentNode) br.parentNode.replaceChild(document.createTextNode('\n'), br);
                });

                // Append block separators for structural tags
                clone.querySelectorAll(
                    'p, div, section, article, li, tr, figure, blockquote, h1, h2, h3, h4, h5, h6'
                ).forEach(block => {
                    block.appendChild(document.createTextNode('\n\n'));
                });

                resultText = (clone.textContent || '')
                    .replace(/ /g, ' ')
                    .split('\n')
                    .map(s => s.trim())
                    .filter(s => s.length > 0)
                    .join('\n\n');
            } else {
                resultText = (document.body ? (document.body.innerText || document.body.textContent || '').trim() : "");
            }

            return {
                title: title || "Chapter",
                content: resultText
            };
        })()
    """

    /**
     * Universal Metadata Extractor.
     *
     * Extracts Book Title, Author, Cover Image, and Synopsis using:
     * 1. JSON-LD Schema (<script type="application/ld+json">)
     * 2. OpenGraph & Twitter Meta Tags (og:title, og:image, og:description, etc.)
     * 3. Schema.org Microdata (itemprop="name", itemprop="author", itemprop="image", itemprop="description")
     * 4. Multi-heuristic DOM fallbacks for cover, author, synopsis, and title.
     */
    const val BOOK_INFO_JS = """
        (() => {
            const cleanText = (t) => (t || '').replace(/\s+/g, ' ').trim();
            const attr = (s, a) => {
                try {
                    const e = document.querySelector(s);
                    return e ? (e.getAttribute(a) || "") : "";
                } catch (e) { return ""; }
            };
            const textOf = (s) => {
                try {
                    const e = document.querySelector(s);
                    return e ? (e.innerText || e.textContent || '').trim() : "";
                } catch (e) { return ""; }
            };

            let title = "";
            let author = "";
            let synopsis = "";
            let cover = "";

            // --- 1. JSON-LD Schema Parsing ---
            try {
                const ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (const script of ldScripts) {
                    try {
                        const data = JSON.parse(script.textContent);
                        const items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                        for (const item of items) {
                            if (!item) continue;
                            const type = (item['@type'] || '').toString().toLowerCase();
                            if (type.includes('book') || type.includes('creativework') || type.includes('novel') || type.includes('article') || type.includes('webpage') || !title) {
                                if (!title && (item.name || item.headline)) {
                                    title = cleanText(item.name || item.headline);
                                }
                                if (!author && item.author) {
                                    if (typeof item.author === 'string') {
                                        author = cleanText(item.author);
                                    } else if (Array.isArray(item.author) && item.author[0]) {
                                        author = cleanText(item.author[0].name || item.author[0]);
                                    } else if (typeof item.author === 'object') {
                                        author = cleanText(item.author.name || '');
                                    }
                                }
                                if (!synopsis && (item.description || item.abstract)) {
                                    synopsis = cleanText(item.description || item.abstract);
                                }
                                if (!cover && (item.image || item.thumbnailUrl)) {
                                    const imgVal = item.image || item.thumbnailUrl;
                                    if (typeof imgVal === 'string') {
                                        cover = imgVal;
                                    } else if (Array.isArray(imgVal) && imgVal[0]) {
                                        cover = typeof imgVal[0] === 'string' ? imgVal[0] : (imgVal[0].url || '');
                                    } else if (typeof imgVal === 'object') {
                                        cover = imgVal.url || '';
                                    }
                                }
                            }
                        }
                    } catch (err) {}
                }
            } catch (e) {}

            // --- 2. OpenGraph & Twitter Meta Tags ---
            if (!title) {
                title = attr('meta[property="og:title"]', 'content') ||
                        attr('meta[name="twitter:title"]', 'content') ||
                        attr('meta[property="twitter:title"]', 'content') || "";
            }
            if (!author) {
                author = attr('meta[name="author"]', 'content') ||
                         attr('meta[property="article:author"]', 'content') ||
                         attr('meta[property="book:author"]', 'content') || "";
            }
            if (!synopsis) {
                synopsis = attr('meta[property="og:description"]', 'content') ||
                           attr('meta[name="description"]', 'content') ||
                           attr('meta[name="twitter:description"]', 'content') || "";
            }
            if (!cover) {
                cover = attr('meta[property="og:image"]', 'content') ||
                        attr('meta[name="twitter:image"]', 'content') ||
                        attr('meta[property="twitter:image"]', 'content') ||
                        attr('link[rel="image_src"]', 'href') || "";
            }

            // --- 3. Schema.org Microdata Fallbacks ---
            if (!title) title = textOf('[itemprop="name"]');
            if (!author) author = textOf('[itemprop="author"]');
            if (!synopsis) synopsis = textOf('[itemprop="description"]');
            if (!cover) cover = attr('[itemprop="image"]', 'src') || attr('[itemprop="image"]', 'content');

            // --- 4. Heuristic DOM Fallbacks ---
            if (!title) {
                title = textOf('h1.book-title, h1.novel-title, h1.entry-title, .book-info h1, .novel-info h1, .detail h1, h1') ||
                        document.title || "";
            }

            // Clean title of website branding suffixes / prefixes
            title = title.split('|')[0]
                         .split(' - ')[0]
                         .replace(/\s*-\s*Read\s+.*Online/i, '')
                         .replace(/\s*-\s*Light\s+Novel.*/i, '')
                         .replace(/\s*-\s*Web\s*Novel.*/i, '')
                         .trim();

            if (!author) {
                author = textOf('.author-content, .author-name, .author, .novel-author, .book-author');
                if (!author) {
                    document.querySelectorAll('p, span, div, li').forEach(el => {
                        const t = (el.innerText || '').trim();
                        if (!author && /^author\s*[:：]/i.test(t)) {
                            author = t.replace(/^author\s*[:：]\s*/i, '').split('\n')[0].trim();
                        } else if (!author && /^writer\s*[:：]/i.test(t)) {
                            author = t.replace(/^writer\s*[:：]\s*/i, '').split('\n')[0].trim();
                        }
                    });
                }
            }

            if (!synopsis) {
                synopsis = textOf('.book-description, .synopsis, #synopsis, .description, .summary, .intro, .novel-desc, .story-desc, .abstract');
            }

            if (!cover) {
                const imgSelectors = [
                    'img.book-cover', '.book-cover img', '.novel-cover img', '.summary_image img',
                    '.cover img', 'img.cover', 'img[src*="cover"]', 'img[src*="novel"]', 'img[src*="book"]',
                    '.poster img', 'figure.cover img', '.thumb img', '.thumbnail img'
                ];
                for (const sel of imgSelectors) {
                    const el = document.querySelector(sel);
                    if (el) {
                        const src = el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-lazy-src') || "";
                        if (src && !src.includes('avatar') && !src.includes('logo') && !src.includes('icon')) {
                            cover = src;
                            break;
                        }
                    }
                }
            }

            // Resolve relative cover URLs to absolute
            if (cover && !cover.startsWith('http://') && !cover.startsWith('https://')) {
                try {
                    cover = new URL(cover, location.href).href;
                } catch (e) {
                    cover = "";
                }
            }

            return {
                ready: title.length > 0,
                title: title,
                author: author || "Unknown Author",
                synopsis: synopsis || "",
                cover: cover
            };
        })()
    """
}
