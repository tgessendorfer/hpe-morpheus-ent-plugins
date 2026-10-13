// FinOps Insights: latest log entries (dashboard widget) and log history (plugin page).
//
// The server renders a root element ".fi-logs" with the viewer's log sources, taken from the
// database for the signed-in user on the server (see FinopsInsightsData.logSources); this script
// only asks the platform's /api/logs for entries of exactly those servers and draws them.
//
// Rules this file keeps (tests: src/test/js/finops-insights-logs.test.js):
//  - Log messages and server names are written by tenants. Nothing here ever sets innerHTML; every
//    text goes through textContent or setAttribute, links are built from digits only, the level
//    class comes from a whitelist.
//  - ES5 only (the asset pipeline minifies with an ES5 parser) and no dependency: the history page
//    is plain HTML without the Morpheus scripts, so it uses XMLHttpRequest, not jQuery.
//  - Running the file twice is harmless: roots that are already drawn are skipped, new ones are
//    picked up (the dashboard loads every item script after it inserts the item).
(function (win) {
	'use strict';

	var doc = win.document;
	if (!doc) {
		return;
	}
	if (win.__fiLogs) {
		win.__fiLogs.scan();
		return;
	}

	var LEVEL_CLASS = {
		TRACE: 'trace', DEBUG: 'debug', INFO: 'info', NOTICE: 'info', WARN: 'warn', WARNING: 'warn',
		ERROR: 'error', ERR: 'error', FATAL: 'fatal', CRITICAL: 'fatal'
	};
	var LEVEL_LABEL = {
		trace: 'TRACE', debug: 'DEBUG', info: 'INFO', warn: 'WARN', error: 'ERROR', fatal: 'FATAL', other: '-'
	};
	var PAGE_LEVELS = ['INFO', 'WARN', 'ERROR', 'DEBUG'];
	var WINDOWS = [['1h', 3600000], ['24h', 86400000], ['7d', 604800000], ['30d', 2592000000]];
	var ROW_TEXT_MAX = 160;
	var TITLE_TEXT_MAX = 1000;
	var PAGE_TEXT_MAX = 600;
	var FALLBACK_SOURCES = 10;
	var DIGITS = /^\d+$/;

	// ---- text, ids, links -------------------------------------------------------------------

	/** Text that is safe to show: no ANSI sequences, no control characters, one line, at most max. */
	function cleanText(value, max) {
		var s = (value === null || value === undefined) ? '' : String(value);
		s = s.replace(/\u001b\][^\u0007\u001b]*(\u0007|\u001b\\)/g, '');
		s = s.replace(/\u001b\[[0-9;?]*[ -\/]*[@-~]/g, '');
		s = s.replace(/[\r\n\t\u000b\u000c]+/g, ' ');
		s = s.replace(/[\u0000-\u001f\u007f-\u009f]/g, '');
		s = s.replace(/\s+/g, ' ').replace(/^ | $/g, '');
		if (max && s.length > max) {
			s = s.substring(0, max - 1) + '\u2026';
		}
		return s;
	}

	function digitsOnly(value) {
		var s = (value === null || value === undefined) ? '' : String(value);
		return DIGITS.test(s) ? s : null;
	}

	function levelOf(raw) {
		var key = cleanText(raw, 20).toUpperCase();
		var cls = Object.prototype.hasOwnProperty.call(LEVEL_CLASS, key) ? LEVEL_CLASS[key] : 'other';
		return {cls: cls, label: LEVEL_LABEL[cls]};
	}

	/** The instance page on its Logs tab; the server page on its Logs pill when there is no instance. */
	function hrefFor(source) {
		if (!source) {
			return null;
		}
		var instance = digitsOnly(source.i);
		if (instance) {
			return '/provisioning/instances/' + instance + '#!monitoring-logs-tab';
		}
		var server = digitsOnly(source.id);
		if (server) {
			return '/infrastructure/servers/' + server + '#!monitoring-logs';
		}
		return null;
	}

	function parseJson(text, fallback) {
		try {
			var parsed = JSON.parse(text);
			return (parsed === null || parsed === undefined) ? fallback : parsed;
		} catch (e) {
			return fallback;
		}
	}

	// ---- time -------------------------------------------------------------------------------

	/** "2026-10-13T13:00:54+0000" is not ISO 8601 for every browser: insert the colon in the offset. */
	function parseTs(ts) {
		var s = cleanText(ts, 40);
		if (!s) {
			return null;
		}
		var fixed = s.replace(/([+-]\d{2})(\d{2})$/, '$1:$2');
		var d = new Date(fixed);
		if (isNaN(d.getTime())) {
			d = new Date(s);
		}
		return isNaN(d.getTime()) ? null : d;
	}

	function morpheusTime(ts) {
		try {
			var svc = win.Morpheus && win.Morpheus.timeService;
			if (svc && typeof svc.getMoment === 'function') {
				var m = svc.getMoment(ts);
				var text = m && typeof m.format === 'function' ? m.format('L hh:mm:ss A') : '';
				if (typeof text === 'string' && text && text.indexOf('Invalid') < 0) {
					return text;
				}
			}
		} catch (e) {
			// fall through to the browser's time zone
		}
		return null;
	}

	function formatTime(ts) {
		var viaMorpheus = morpheusTime(ts);
		if (viaMorpheus) {
			return cleanText(viaMorpheus, 40);
		}
		var d = parseTs(ts);
		if (!d) {
			return cleanText(ts, 40);
		}
		try {
			return d.toLocaleString();
		} catch (e) {
			return d.toISOString();
		}
	}

	function zoneName() {
		try {
			return win.Intl.DateTimeFormat().resolvedOptions().timeZone || '';
		} catch (e) {
			return '';
		}
	}

	// ---- entries ----------------------------------------------------------------------------

	function normalize(raw) {
		var out = [];
		for (var i = 0; i < raw.length; i++) {
			var e = raw[i];
			if (!e || typeof e !== 'object') {
				continue;
			}
			var d = parseTs(e.ts);
			out.push({
				id: (e._id === null || e._id === undefined) ? '' : String(e._id),
				ts: e.ts, ms: d ? d.getTime() : 0, level: e.level, message: e.message,
				objectId: (e.objectId === null || e.objectId === undefined) ? '' : String(e.objectId)
			});
		}
		return out;
	}

	/** Consecutive entries of one source with the same level and message become one with a count. */
	function collapse(entries) {
		var groups = [];
		var last = null;
		var lastKey = null;
		for (var i = 0; i < entries.length; i++) {
			var e = entries[i];
			var key = e.objectId + '|' + levelOf(e.level).cls + '|' + cleanText(e.message, TITLE_TEXT_MAX);
			if (last && key === lastKey) {
				last.count++;
				continue;
			}
			last = {entry: e, count: 1};
			lastKey = key;
			groups.push(last);
		}
		return groups;
	}

	function single(entries) {
		var groups = [];
		for (var i = 0; i < entries.length; i++) {
			groups.push({entry: entries[i], count: 1});
		}
		return groups;
	}

	// ---- network ----------------------------------------------------------------------------

	function encode(value) {
		return encodeURIComponent(String(value));
	}

	/** Query string with repeated keys for arrays: servers=1&servers=2 (a comma list matches nothing). */
	function toQuery(params) {
		var parts = [];
		for (var key in params) {
			if (!Object.prototype.hasOwnProperty.call(params, key)) {
				continue;
			}
			var v = params[key];
			if (v === null || v === undefined || v === '') {
				continue;
			}
			if (Object.prototype.toString.call(v) === '[object Array]') {
				for (var i = 0; i < v.length; i++) {
					parts.push(encode(key) + '=' + encode(v[i]));
				}
			} else {
				parts.push(encode(key) + '=' + encode(v));
			}
		}
		return parts.join('&');
	}

	function meta(name) {
		try {
			var el = doc.querySelector('meta[name="' + name + '"]');
			return el ? el.getAttribute('content') : null;
		} catch (e) {
			return null;
		}
	}

	function send(method, url, body, done) {
		var Xhr = win.XMLHttpRequest;
		if (!Xhr) {
			done(new Error('no XMLHttpRequest'));
			return;
		}
		var x = new Xhr();
		x.open(method, url, true);
		x.setRequestHeader('Accept', 'application/json');
		x.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
		if (method !== 'GET') {
			x.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
			var token = meta('_csrf');
			var header = meta('_csrf_header');
			if (token && header) {
				x.setRequestHeader(header, token);
			}
		}
		x.onreadystatechange = function () {
			if (x.readyState !== 4) {
				return;
			}
			if (x.status < 200 || x.status >= 300) {
				done(new Error('HTTP ' + x.status));
				return;
			}
			var parsed = parseJson(x.responseText, null);
			if (!parsed || typeof parsed !== 'object' || parsed.success === false) {
				done(new Error('unusable response'));
				return;
			}
			done(null, parsed);
		};
		x.send(body || null);
	}

	function toNumber(value, fallback) {
		var n = Number(value);
		return isNaN(n) ? fallback : n;
	}

	/**
	 * One page of entries of the given servers, newest first. The platform API takes explicit server
	 * ids; when it refuses the browser session, the UI's own log route is asked once per server
	 * (that route applies the viewer's scope itself) and the answers are merged, newest first.
	 */
	function load(ctx, query, done) {
		var ids = query.ids;
		var api = {servers: ids, max: query.max, offset: query.offset, sort: 'ts', order: 'desc',
			startMs: query.startMs, level: query.levels};
		send('GET', '/api/logs?' + toQuery(api), null, function (err, body) {
			if (!err && Object.prototype.toString.call(body.data) === '[object Array]') {
				var entries = normalize(body.data);
				var total = toNumber(body.total, toNumber(body.grandTotal, null));
				done(null, {entries: entries, total: total, paged: true});
				return;
			}
			loadViaUi(ids, query, done);
		});
	}

	function loadViaUi(ids, query, done) {
		var use = ids.slice(0, FALLBACK_SOURCES);
		var pending = use.length;
		var merged = [];
		var failed = 0;
		if (!pending) {
			done(null, {entries: [], total: 0, paged: false});
			return;
		}
		function finished() {
			pending--;
			if (pending > 0) {
				return;
			}
			if (failed === use.length) {
				done(new Error('no log route answered'));
				return;
			}
			merged.sort(function (a, b) {
				return b.ms - a.ms;
			});
			done(null, {entries: merged.slice(0, query.max), total: merged.length, paged: false});
		}
		for (var i = 0; i < use.length; i++) {
			(function (id) {
				var form = toQuery({serverId: id, max: query.max, offset: 0, startMs: query.startMs,
					endMs: Date.now() + 300000, level: query.levels});
				send('POST', '/logs/logs.json', form, function (err, body) {
					if (err || Object.prototype.toString.call(body.data) !== '[object Array]') {
						failed++;
					} else {
						merged = merged.concat(normalize(body.data));
					}
					finished();
				});
			})(use[i]);
		}
	}

	// ---- DOM helpers ------------------------------------------------------------------------

	function el(tag, cls, text) {
		var node = doc.createElement(tag);
		if (cls) {
			node.className = cls;
		}
		if (text !== undefined && text !== null) {
			node.textContent = text;
		}
		return node;
	}

	function clear(node) {
		while (node.firstChild) {
			node.removeChild(node.firstChild);
		}
	}

	/** The standard Log History widget was hidden by the server rendered rule; show it again. */
	function restoreStandard() {
		try {
			var rules = doc.querySelectorAll('style[data-fi-swap]');
			for (var i = 0; i < rules.length; i++) {
				if (rules[i].parentNode) {
					rules[i].parentNode.removeChild(rules[i]);
				}
			}
		} catch (e) {
			// nothing to restore
		}
	}

	function attached(node) {
		var rootEl = doc.documentElement;
		if (!rootEl || typeof rootEl.contains !== 'function') {
			return true;
		}
		return rootEl.contains(node);
	}

	function fill(template, values) {
		return String(template || '').replace(/\{(\d+)\}/g, function (all, n) {
			var v = values[Number(n)];
			return (v === null || v === undefined) ? '' : String(v);
		});
	}

	function sourceName(source, entry) {
		return source ? cleanText(source.n, 120) || ('#' + cleanText(source.id, 20)) : cleanText(entry.objectId, 20) || '-';
	}

	function rowFor(group, sources, textMax) {
		var e = group.entry;
		var src = Object.prototype.hasOwnProperty.call(sources, e.objectId) ? sources[e.objectId] : null;
		var lv = levelOf(e.level);
		var li = el('li', 'fi-log-row');
		var time = el('span', 'fi-log-time', formatTime(e.ts));
		time.setAttribute('title', cleanText(e.ts, 40));
		li.appendChild(time);
		li.appendChild(el('span', 'fi-log-level fi-lv-' + lv.cls, lv.label));
		var href = hrefFor(src);
		var name = sourceName(src, e);
		var holder;
		if (href) {
			holder = el('a', 'fi-log-src', name);
			holder.setAttribute('href', href);
		} else {
			holder = el('span', 'fi-log-src', name);
		}
		holder.setAttribute('title', name);
		li.appendChild(holder);
		var msg = el('span', 'fi-log-msg', cleanText(e.message, textMax));
		msg.setAttribute('title', cleanText(e.message, TITLE_TEXT_MAX));
		li.appendChild(msg);
		if (group.count > 1) {
			li.appendChild(el('span', 'fi-log-count', '\u00d7' + group.count));
		}
		return li;
	}

	function drawRows(list, groups, sources, textMax) {
		clear(list);
		for (var i = 0; i < groups.length; i++) {
			list.appendChild(rowFor(groups[i], sources, textMax));
		}
	}

	function readSources(root) {
		var raw = parseJson(root.getAttribute('data-sources'), []);
		var map = {};
		var ids = [];
		if (Object.prototype.toString.call(raw) !== '[object Array]') {
			return {map: map, ids: ids};
		}
		for (var i = 0; i < raw.length && ids.length < 50; i++) {
			var s = raw[i];
			var id = s ? digitsOnly(s.id) : null;
			if (!id || Object.prototype.hasOwnProperty.call(map, id)) {
				continue;
			}
			map[id] = {id: id, n: s.n, i: digitsOnly(s.i)};
			ids.push(id);
		}
		return {map: map, ids: ids};
	}

	// ---- widget -----------------------------------------------------------------------------

	function startWidget(root, ctx) {
		var days = toNumber(root.getAttribute('data-window-days'), 7);
		var show = toNumber(root.getAttribute('data-show'), 10);
		var fetchMax = toNumber(root.getAttribute('data-max'), 40);
		var status = el('div', 'fi-log-status', ctx.t.logs_loading);
		var list = el('ul', 'fi-log-list');
		clear(root);
		root.appendChild(status);
		root.appendChild(list);

		var serial = 0;
		var handler = null;

		function refresh() {
			if (handler && !attached(root)) {
				try {
					win.jQuery(doc).off('morpheus:refresh', handler);
				} catch (e) {
					// already gone
				}
				return;
			}
			if (!ctx.ids.length) {
				status.textContent = ctx.t.logs_noSources || '';
				return;
			}
			var mine = ++serial;
			load(ctx, {ids: ctx.ids, max: fetchMax, offset: 0, startMs: Date.now() - days * 86400000}, function (err, res) {
				if (mine !== serial) {
					return;
				}
				if (err) {
					clear(list);
					status.textContent = ctx.t.logs_error || '';
					restoreStandard();
					return;
				}
				var groups = collapse(res.entries).slice(0, show);
				drawRows(list, groups, ctx.map, ROW_TEXT_MAX);
				status.textContent = groups.length ? '' : fill(ctx.t.logs_empty, [days]);
			});
		}

		refresh();
		try {
			if (win.jQuery) {
				handler = refresh;
				win.jQuery(doc).on('morpheus:refresh', handler);
			}
		} catch (e) {
			// the widget works without auto refresh
		}
	}

	// ---- history page -----------------------------------------------------------------------

	function option(value, label, selected) {
		var o = el('option', null, label);
		o.setAttribute('value', value);
		if (selected) {
			o.setAttribute('selected', 'selected');
		}
		return o;
	}

	function labelled(text, control) {
		var label = el('label', 'fi-field');
		label.appendChild(el('span', 'fi-field-name', text));
		label.appendChild(control);
		return label;
	}

	function startPage(root, ctx) {
		var pageSize = toNumber(root.getAttribute('data-page-size'), 100);
		var initial = parseJson(root.getAttribute('data-initial'), {});
		var state = {
			window: '24h', levels: PAGE_LEVELS.slice(0), source: '', text: '', collapse: false,
			entries: [], total: null, offset: 0, paged: true, busy: false, serial: 0, startMs: 0
		};
		var i;
		for (i = 0; i < WINDOWS.length; i++) {
			if (initial.window === WINDOWS[i][0]) {
				state.window = initial.window;
			}
		}
		if (Object.prototype.toString.call(initial.levels) === '[object Array]') {
			var wanted = [];
			for (i = 0; i < PAGE_LEVELS.length; i++) {
				if (initial.levels.indexOf(PAGE_LEVELS[i]) >= 0) {
					wanted.push(PAGE_LEVELS[i]);
				}
			}
			state.levels = wanted;
		}
		if (initial.source !== null && initial.source !== undefined && ctx.map[String(initial.source)]) {
			state.source = String(initial.source);
		}
		state.text = cleanText(initial.q, 100);
		state.collapse = initial.collapse === true;

		clear(root);
		var bar = el('div', 'fi-bar');
		var windowSel = el('select', 'fi-input');
		for (i = 0; i < WINDOWS.length; i++) {
			windowSel.appendChild(option(WINDOWS[i][0], ctx.t['logs_window_' + WINDOWS[i][0]] || WINDOWS[i][0], WINDOWS[i][0] === state.window));
		}
		bar.appendChild(labelled(ctx.t.logs_window, windowSel));

		var levelBox = el('div', 'fi-levels');
		var levelInputs = {};
		for (i = 0; i < PAGE_LEVELS.length; i++) {
			var cb = el('input', null);
			cb.setAttribute('type', 'checkbox');
			cb.setAttribute('value', PAGE_LEVELS[i]);
			if (state.levels.indexOf(PAGE_LEVELS[i]) >= 0) {
				cb.setAttribute('checked', 'checked');
				cb.checked = true;
			}
			levelInputs[PAGE_LEVELS[i]] = cb;
			var lab = el('label', 'fi-check');
			lab.appendChild(cb);
			var badge = levelOf(PAGE_LEVELS[i]);
			lab.appendChild(el('span', 'fi-log-level fi-lv-' + badge.cls, badge.label));
			levelBox.appendChild(lab);
		}
		bar.appendChild(labelled(ctx.t.logs_level, levelBox));

		var sourceSel = el('select', 'fi-input');
		sourceSel.appendChild(option('', ctx.t.logs_allSources || '', state.source === ''));
		for (i = 0; i < ctx.ids.length; i++) {
			sourceSel.appendChild(option(ctx.ids[i], sourceName(ctx.map[ctx.ids[i]], {objectId: ctx.ids[i]}), ctx.ids[i] === state.source));
		}
		bar.appendChild(labelled(ctx.t.logs_source, sourceSel));

		var textIn = el('input', 'fi-input');
		textIn.setAttribute('type', 'search');
		textIn.setAttribute('placeholder', ctx.t.logs_textHint || '');
		textIn.setAttribute('maxlength', '100');
		textIn.setAttribute('value', state.text);
		textIn.value = state.text;
		bar.appendChild(labelled(ctx.t.logs_text, textIn));

		var collapseCb = el('input', null);
		collapseCb.setAttribute('type', 'checkbox');
		if (state.collapse) {
			collapseCb.setAttribute('checked', 'checked');
			collapseCb.checked = true;
		}
		var collapseLab = el('label', 'fi-check fi-collapse');
		collapseLab.appendChild(collapseCb);
		collapseLab.appendChild(el('span', null, ctx.t.logs_collapse));
		bar.appendChild(collapseLab);
		root.appendChild(bar);

		var status = el('div', 'fi-log-status', ctx.t.logs_loading);
		var list = el('ul', 'fi-log-list fi-log-list-page');
		var more = el('button', 'fi-more', ctx.t.logs_more);
		more.setAttribute('type', 'button');
		var foot = el('div', 'fi-foot');
		foot.appendChild(more);
		var zone = zoneName();
		var note = el('div', 'fi-note', (zone ? fill(ctx.t.logs_timezone, [zone]) + ' ' : '') + (ctx.t.logs_retention || ''));
		root.appendChild(status);
		root.appendChild(list);
		root.appendChild(foot);
		root.appendChild(note);

		function matches(entry) {
			if (!state.text) {
				return true;
			}
			var needle = state.text.toLowerCase();
			var src = Object.prototype.hasOwnProperty.call(ctx.map, entry.objectId) ? ctx.map[entry.objectId] : null;
			var hay = (cleanText(entry.message, TITLE_TEXT_MAX) + ' ' + sourceName(src, entry)).toLowerCase();
			return hay.indexOf(needle) >= 0;
		}

		function draw(message) {
			var visible = [];
			for (var k = 0; k < state.entries.length; k++) {
				if (matches(state.entries[k])) {
					visible.push(state.entries[k]);
				}
			}
			var groups = state.collapse ? collapse(visible) : single(visible);
			drawRows(list, groups, ctx.map, PAGE_TEXT_MAX);
			var text = message || '';
			if (!text) {
				if (!groups.length) {
					text = ctx.t.logs_pageEmpty || '';
				} else if (state.total !== null && state.total >= state.entries.length) {
					text = fill(ctx.t.logs_showingOf, [visible.length, state.total]);
				} else {
					text = fill(ctx.t.logs_showing, [visible.length]);
				}
			}
			status.textContent = text;
			var hasMore = state.paged && state.entries.length > 0 &&
				(state.total !== null ? state.entries.length < state.total : state.entries.length % pageSize === 0);
			more.style.display = hasMore ? '' : 'none';
			more.disabled = state.busy;
		}

		function sourceIds() {
			return state.source ? [state.source] : ctx.ids;
		}

		function request(append) {
			// an answer of an earlier request must not overwrite what is shown now
			var serial = ++state.serial;
			if (!ctx.ids.length) {
				state.busy = false;
				draw(ctx.t.logs_noSources);
				return;
			}
			if (!state.levels.length) {
				state.busy = false;
				state.entries = [];
				draw(ctx.t.logs_noLevel);
				return;
			}
			state.busy = true;
			if (!append) {
				var windowMs = 86400000;
				for (var w = 0; w < WINDOWS.length; w++) {
					if (WINDOWS[w][0] === state.window) {
						windowMs = WINDOWS[w][1];
					}
				}
				// the same lower bound for every page of one view
				state.startMs = Date.now() - windowMs;
				state.offset = 0;
				status.textContent = ctx.t.logs_loading;
			}
			var query = {ids: sourceIds(), max: pageSize, offset: state.offset, startMs: state.startMs,
				levels: state.levels.length === PAGE_LEVELS.length ? null : state.levels.join('|')};
			load(ctx, query, function (err, res) {
				if (serial !== state.serial) {
					return;
				}
				state.busy = false;
				if (err) {
					if (!append) {
						state.entries = [];
					}
					draw(ctx.t.logs_error);
					return;
				}
				if (append) {
					// entries written since the first page push older ones down: skip what is shown already
					var seen = {};
					for (var e = 0; e < state.entries.length; e++) {
						if (state.entries[e].id) {
							seen[state.entries[e].id] = true;
						}
					}
					var fresh = [];
					for (var f = 0; f < res.entries.length; f++) {
						var id = res.entries[f].id;
						if (!id || !Object.prototype.hasOwnProperty.call(seen, id)) {
							fresh.push(res.entries[f]);
						}
					}
					state.entries = state.entries.concat(fresh);
					state.offset += res.entries.length;
				} else {
					state.entries = res.entries;
					state.offset = res.entries.length;
				}
				state.total = res.total;
				state.paged = res.paged;
				draw();
			});
		}

		windowSel.addEventListener('change', function () {
			state.window = windowSel.value;
			request(false);
		});
		for (i = 0; i < PAGE_LEVELS.length; i++) {
			(function (level) {
				levelInputs[level].addEventListener('change', function () {
					var next = [];
					for (var n = 0; n < PAGE_LEVELS.length; n++) {
						if (levelInputs[PAGE_LEVELS[n]].checked) {
							next.push(PAGE_LEVELS[n]);
						}
					}
					state.levels = next;
					request(false);
				});
			})(PAGE_LEVELS[i]);
		}
		sourceSel.addEventListener('change', function () {
			state.source = ctx.map[sourceSel.value] ? sourceSel.value : '';
			request(false);
		});
		textIn.addEventListener('input', function () {
			state.text = cleanText(textIn.value, 100);
			draw();
		});
		collapseCb.addEventListener('change', function () {
			state.collapse = !!collapseCb.checked;
			draw();
		});
		more.addEventListener('click', function () {
			if (!state.busy) {
				request(true);
			}
		});

		more.style.display = 'none';
		request(false);
	}

	// ---- start ------------------------------------------------------------------------------

	function init(root) {
		if (root.getAttribute('data-fi-ready') === '1') {
			return;
		}
		root.setAttribute('data-fi-ready', '1');
		var src = readSources(root);
		var t = parseJson(root.getAttribute('data-labels'), {});
		var ctx = {map: src.map, ids: src.ids, t: (t && typeof t === 'object') ? t : {}};
		if (root.getAttribute('data-mode') === 'page') {
			startPage(root, ctx);
		} else {
			startWidget(root, ctx);
		}
	}

	function scan() {
		var roots = doc.querySelectorAll('.fi-logs');
		for (var i = 0; i < roots.length; i++) {
			init(roots[i]);
		}
	}

	win.__fiLogs = {
		scan: scan,
		util: {
			cleanText: cleanText, levelOf: levelOf, hrefFor: hrefFor, parseTs: parseTs, formatTime: formatTime,
			collapse: collapse, toQuery: toQuery, normalize: normalize
		}
	};
	scan();
})(window);
