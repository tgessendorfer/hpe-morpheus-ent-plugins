// Tests for src/assets/javascripts/finops-insights-logs.js, run with plain node (no packages):
//   node src/test/js/finops-insights-logs.test.js
// The browser is replaced by a small DOM and XMLHttpRequest stub that records what the script does.
'use strict';
process.env.TZ = 'UTC';

var assert = require('assert');
var fs = require('fs');
var path = require('path');
var vm = require('vm');

// FI_LOGS_JS points the tests at another build of the script, for example the minified file in the jar.
var SOURCE = fs.readFileSync(process.env.FI_LOGS_JS ||
	path.join(__dirname, '..', '..', 'assets', 'javascripts', 'finops-insights-logs.js'), 'utf8');

// ---- stub DOM -------------------------------------------------------------------------------

function Element(doc, tag) {
	this.ownerDocument = doc;
	this.tagName = String(tag).toUpperCase();
	this.children = [];
	this.parentNode = null;
	this.attributes = {};
	this.className = '';
	this.style = {};
	this.listeners = {};
	this.value = '';
	this.checked = false;
	this.disabled = false;
	this._text = '';
}
Object.defineProperty(Element.prototype, 'textContent', {
	get: function () {
		var out = this._text;
		for (var i = 0; i < this.children.length; i++) {
			out += this.children[i].textContent;
		}
		return out;
	},
	set: function (v) {
		this.children = [];
		this._text = String(v);
	}
});
Object.defineProperty(Element.prototype, 'firstChild', {
	get: function () {
		return this.children.length ? this.children[0] : null;
	}
});
Element.prototype.appendChild = function (child) {
	child.parentNode = this;
	this.children.push(child);
	return child;
};
Element.prototype.removeChild = function (child) {
	var i = this.children.indexOf(child);
	if (i >= 0) {
		this.children.splice(i, 1);
		child.parentNode = null;
	}
	return child;
};
Element.prototype.setAttribute = function (name, value) {
	this.attributes[name] = String(value);
	if (name === 'value') {
		this.value = String(value);
	}
};
Element.prototype.getAttribute = function (name) {
	return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
};
Element.prototype.addEventListener = function (type, fn) {
	(this.listeners[type] = this.listeners[type] || []).push(fn);
};
Element.prototype.fire = function (type) {
	var list = this.listeners[type] || [];
	for (var i = 0; i < list.length; i++) {
		list[i].call(this, {type: type, target: this});
	}
};
Element.prototype.walk = function (fn) {
	fn(this);
	for (var i = 0; i < this.children.length; i++) {
		this.children[i].walk(fn);
	}
};
Element.prototype.byClass = function (cls) {
	var out = [];
	this.walk(function (n) {
		if ((' ' + n.className + ' ').indexOf(' ' + cls + ' ') >= 0) {
			out.push(n);
		}
	});
	return out;
};
Element.prototype.byTag = function (tag) {
	var out = [];
	this.walk(function (n) {
		if (n.tagName === tag.toUpperCase()) {
			out.push(n);
		}
	});
	return out;
};

function makeDocument() {
	var doc = {created: [], metas: {}};
	doc.body = new Element(doc, 'body');
	doc.createElement = function (tag) {
		doc.created.push(String(tag).toUpperCase());
		return new Element(doc, tag);
	};
	doc.querySelectorAll = function (selector) {
		assert.strictEqual(selector, '.fi-logs');
		return doc.body.byClass('fi-logs');
	};
	doc.querySelector = function (selector) {
		var m = /^meta\[name="([^"]+)"\]$/.exec(selector);
		if (m && Object.prototype.hasOwnProperty.call(doc.metas, m[1])) {
			var meta = new Element(doc, 'meta');
			meta.setAttribute('content', doc.metas[m[1]]);
			return meta;
		}
		return null;
	};
	return doc;
}

function makeRoot(doc, attrs) {
	var root = doc.createElement('div');
	root.className = 'fi-logs';
	for (var k in attrs) {
		root.setAttribute(k, typeof attrs[k] === 'string' ? attrs[k] : JSON.stringify(attrs[k]));
	}
	doc.body.appendChild(root);
	return root;
}

// ---- stub XMLHttpRequest and window ---------------------------------------------------------

function makeWindow(router) {
	var doc = makeDocument();
	var requests = [];
	function Xhr() {
		this.headers = {};
		this.readyState = 0;
	}
	Xhr.prototype.open = function (method, url) {
		this.method = method;
		this.url = url;
	};
	Xhr.prototype.setRequestHeader = function (k, v) {
		this.headers[k] = v;
	};
	Xhr.prototype.send = function (body) {
		this.body = body || null;
		requests.push(this);
		var res = router(this, requests.length);
		this.readyState = 4;
		this.status = res.status;
		this.responseText = typeof res.body === 'string' ? res.body : JSON.stringify(res.body);
		if (this.onreadystatechange) {
			this.onreadystatechange();
		}
	};
	var win = {document: doc, XMLHttpRequest: Xhr, requests: requests, __handlers: {}};
	win.window = win;
	return win;
}

function run(win) {
	vm.runInNewContext(SOURCE, win);
}

function query(url) {
	var q = url.split('?')[1] || '';
	var out = {};
	q.split('&').forEach(function (pair) {
		if (!pair) {
			return;
		}
		var kv = pair.split('=');
		var k = decodeURIComponent(kv[0]);
		var v = decodeURIComponent(kv[1] || '');
		(out[k] = out[k] || []).push(v);
	});
	return out;
}

function entry(ts, level, message, objectId) {
	return {ts: ts, level: level, message: message, objectId: String(objectId), typeCode: 'server', sourceType: 'x'};
}

var LABELS = {
	logs_loading: 'Loading', logs_empty: 'No entries in {0} days', logs_error: 'Failed', logs_noSources: 'No sources',
	logs_window: 'Time range', logs_window_1h: '1h', logs_window_24h: '24h', logs_window_7d: '7d', logs_window_30d: '30d',
	logs_level: 'Level', logs_source: 'Source', logs_allSources: 'All sources', logs_text: 'Text', logs_textHint: 'filter',
	logs_collapse: 'Collapse', logs_more: 'More', logs_noLevel: 'Pick a level', logs_pageEmpty: 'Nothing',
	logs_showing: 'Showing {0}', logs_showingOf: 'Showing {0} of {1}', logs_timezone: 'Times in {0}.', logs_retention: 'Retention note'
};

var SOURCES = [{id: 31, n: 'web-01', i: 410}, {id: 32, n: 'db-01', i: null}];
var passed = 0;
function test(name, fn) {
	try {
		fn();
		passed++;
		console.log('ok   ' + name);
	} catch (e) {
		console.log('FAIL ' + name);
		console.log(e && e.stack ? e.stack : e);
		process.exitCode = 1;
	}
}

function widgetWindow(router, sources, extra) {
	var win = makeWindow(router);
	var attrs = {'data-mode': 'widget', 'data-sources': sources || SOURCES, 'data-labels': LABELS,
		'data-window-days': '7', 'data-max': '40', 'data-show': '10'};
	for (var k in (extra || {})) {
		attrs[k] = extra[k];
	}
	var root = makeRoot(win.document, attrs);
	return {win: win, root: root};
}

// ---- text, time, links ----------------------------------------------------------------------

test('cleanText strips ANSI sequences, control characters and collapses whitespace', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	run(w);
	var clean = w.__fiLogs.util.cleanText;
	assert.strictEqual(clean('\u001b[31mERROR\u001b[0m boom\n'), 'ERROR boom');
	assert.strictEqual(clean('a\u0000b\u0007c\r\n\t d   e'), 'abc d e');
	assert.strictEqual(clean('\u001b]0;title\u0007text'), 'text');
	assert.strictEqual(clean(null), '');
	assert.strictEqual(clean('x'.repeat(200), 160).length, 160);
	assert.strictEqual(clean('x'.repeat(200), 160).slice(-1), '…');
});

test('timestamps with +0000 are read as UTC and shown in the browser time zone', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	run(w);
	var u = w.__fiLogs.util;
	assert.strictEqual(u.parseTs('2026-10-13T13:00:54+0000').getTime(), Date.UTC(2026, 9, 13, 13, 0, 54));
	assert.strictEqual(u.parseTs('2026-10-13T13:00:54Z').getTime(), Date.UTC(2026, 9, 13, 13, 0, 54));
	assert.strictEqual(u.parseTs('2026-10-13T15:00:54+0200').getTime(), Date.UTC(2026, 9, 13, 13, 0, 54));
	assert.strictEqual(u.parseTs('not a date'), null);
	assert.strictEqual(u.formatTime('2026-10-13T13:00:54+0000'), new Date(Date.UTC(2026, 9, 13, 13, 0, 54)).toLocaleString());
	assert.strictEqual(u.formatTime('garbage<b>'), 'garbage<b>');
});

test('the Morpheus time service is used when it is there, with the documented format', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	var asked = [];
	w.Morpheus = {timeService: {getMoment: function (ts) {
		asked.push(ts);
		return {format: function (f) { asked.push(f); return '10/13/2026 01:00:54 PM'; }};
	}}};
	run(w);
	assert.strictEqual(w.__fiLogs.util.formatTime('2026-10-13T13:00:54+0000'), '10/13/2026 01:00:54 PM');
	assert.deepStrictEqual(asked, ['2026-10-13T13:00:54+0000', 'L hh:mm:ss A']);
});

test('a throwing Morpheus time service falls back to the browser', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	w.Morpheus = {timeService: {getMoment: function () { throw new Error('nope'); }}};
	run(w);
	assert.strictEqual(w.__fiLogs.util.formatTime('2026-10-13T13:00:54+0000'), new Date(Date.UTC(2026, 9, 13, 13, 0, 54)).toLocaleString());
});

test('links are built from digits only', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	run(w);
	var href = w.__fiLogs.util.hrefFor;
	assert.strictEqual(href({id: '31', i: '410'}), '/provisioning/instances/410#!monitoring-logs-tab');
	assert.strictEqual(href({id: '31', i: null}), '/infrastructure/servers/31#!monitoring-logs');
	assert.strictEqual(href({id: '31', i: '4 10'}), '/infrastructure/servers/31#!monitoring-logs');
	assert.strictEqual(href({id: '31', i: 'javascript:alert(1)'}), '/infrastructure/servers/31#!monitoring-logs');
	assert.strictEqual(href({id: '31/../x', i: '12abc'}), null);
	assert.strictEqual(href(null), null);
});

test('levels come from a whitelist', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	run(w);
	// objects of the script's own context: compare as JSON
	var lv = function (v) { return JSON.stringify(w.__fiLogs.util.levelOf(v)); };
	assert.strictEqual(lv('warn'), '{"cls":"warn","label":"WARN"}');
	assert.strictEqual(lv('WARNING'), '{"cls":"warn","label":"WARN"}');
	assert.strictEqual(lv('Err'), '{"cls":"error","label":"ERROR"}');
	assert.strictEqual(lv('"><img src=x onerror=1>'), '{"cls":"other","label":"-"}');
	assert.strictEqual(lv(undefined), '{"cls":"other","label":"-"}');
	assert.strictEqual(lv('constructor'), '{"cls":"other","label":"-"}');
	assert.strictEqual(lv('__proto__'), '{"cls":"other","label":"-"}');
});

test('repeated query keys are written as servers=1&servers=2, never as a comma list', function () {
	var w = makeWindow(function () { return {status: 200, body: {data: []}}; });
	run(w);
	assert.strictEqual(w.__fiLogs.util.toQuery({servers: ['31', '32'], max: 40, level: null, q: ''}), 'servers=31&servers=32&max=40');
	assert.strictEqual(w.__fiLogs.util.toQuery({level: 'WARN|ERROR'}), 'level=WARN%7CERROR');
});

// ---- widget ---------------------------------------------------------------------------------

test('widget: asks the log API for exactly the embedded sources and draws rows', function () {
	var now = Date.now();
	var t = widgetWindow(function () {
		return {status: 200, body: {success: true, total: 2, data: [
			entry('2026-10-13T13:00:54+0000', 'INFO', 'started', 31),
			entry('2026-10-13T12:00:00+0000', 'ERROR', 'disk full', 32)]}};
	});
	run(t.win);
	assert.strictEqual(t.win.requests.length, 1);
	var req = t.win.requests[0];
	assert.strictEqual(req.method, 'GET');
	assert.strictEqual(req.url.split('?')[0], '/api/logs');
	var q = query(req.url);
	assert.deepStrictEqual(q.servers, ['31', '32']);
	assert.deepStrictEqual([q.max, q.offset, q.sort, q.order], [['40'], ['0'], ['ts'], ['desc']]);
	assert.strictEqual(q.level, undefined);
	var start = Number(q.startMs[0]);
	assert.ok(Math.abs((now - start) - 7 * 86400000) < 60000, 'startMs is about 7 days ago');
	assert.strictEqual(req.headers['X-Requested-With'], 'XMLHttpRequest');
	var rows = t.root.byClass('fi-log-row');
	assert.strictEqual(rows.length, 2);
	var first = rows[0];
	assert.strictEqual(first.byClass('fi-log-msg')[0].textContent, 'started');
	assert.strictEqual(first.byClass('fi-log-level')[0].textContent, 'INFO');
	assert.strictEqual(first.byClass('fi-log-src')[0].textContent, 'web-01');
	assert.strictEqual(first.byClass('fi-log-src')[0].getAttribute('href'), '/provisioning/instances/410#!monitoring-logs-tab');
	assert.strictEqual(rows[1].byClass('fi-log-src')[0].getAttribute('href'), '/infrastructure/servers/32#!monitoring-logs');
});

test('widget: a hostile message and server name stay text, ANSI and control characters are gone', function () {
	var hostileName = '"><script>alert(1)</script>';
	var t = widgetWindow(function () {
		return {status: 200, body: {data: [
			entry('2026-10-13T13:00:54+0000', 'warn"><img src=x onerror=alert(1)>',
				'<img src=x onerror=alert(1)>\u001b[31m red \u001b[0m\u0007bell\nnext line', 31),
			entry('2026-10-13T13:00:53+0000', 'INFO', 'plain', 99)]}};
	}, [{id: 31, n: hostileName, i: 410}]);
	run(t.win);
	var created = t.win.document.created;
	['IMG', 'SCRIPT', 'IFRAME', 'A'].forEach(function (tag) {
		if (tag !== 'A') {
			assert.ok(created.indexOf(tag) < 0, tag + ' must never be created');
		}
	});
	var rows = t.root.byClass('fi-log-row');
	assert.strictEqual(rows.length, 2);
	assert.strictEqual(rows[0].byClass('fi-log-msg')[0].textContent, '<img src=x onerror=alert(1)> red bell next line');
	assert.strictEqual(rows[0].byClass('fi-log-src')[0].textContent, hostileName);
	assert.strictEqual(rows[0].byClass('fi-log-level')[0].textContent, '-');
	assert.strictEqual(rows[0].byClass('fi-log-level')[0].className, 'fi-log-level fi-lv-other');
	assert.ok(!/[\u0000-\u0008\u000b-\u001f]/.test(t.root.textContent));
	// an object id that is not one of the embedded sources is plain text without a link
	assert.strictEqual(rows[1].byClass('fi-log-src')[0].tagName, 'SPAN');
	assert.strictEqual(rows[1].byClass('fi-log-src')[0].textContent, '99');
});

test('widget: a source with a non-numeric id is dropped and never sent to the API', function () {
	var t = widgetWindow(function () { return {status: 200, body: {data: []}}; },
		[{id: 31, n: 'ok', i: null}, {id: '32; DROP', n: 'bad', i: null}, {id: '33', n: 'also ok', i: null}, {id: 31, n: 'dup', i: null}]);
	run(t.win);
	assert.deepStrictEqual(query(t.win.requests[0].url).servers, ['31', '33']);
});

test('widget: at most 50 sources are sent', function () {
	var many = [];
	for (var i = 1; i <= 80; i++) {
		many.push({id: i, n: 's' + i, i: null});
	}
	var t = widgetWindow(function () { return {status: 200, body: {data: []}}; }, many);
	run(t.win);
	assert.strictEqual(query(t.win.requests[0].url).servers.length, 50);
});

test('widget: consecutive identical entries collapse with a count and ten groups are shown', function () {
	var data = [];
	for (var i = 0; i < 3; i++) { data.push(entry('2026-10-13T13:00:5' + (9 - i) + '+0000', 'INFO', 'retry', 31)); }
	data.push(entry('2026-10-13T13:00:50+0000', 'INFO', 'other', 31));
	data.push(entry('2026-10-13T13:00:49+0000', 'INFO', 'retry', 31));
	for (var j = 0; j < 20; j++) { data.push(entry('2026-10-13T12:00:' + (10 + j) + '+0000', 'WARN', 'm' + j, 32)); }
	var t = widgetWindow(function () { return {status: 200, body: {data: data}}; });
	run(t.win);
	var rows = t.root.byClass('fi-log-row');
	assert.strictEqual(rows.length, 10);
	assert.strictEqual(rows[0].byClass('fi-log-count')[0].textContent, '×3');
	assert.strictEqual(rows[1].byClass('fi-log-count').length, 0);
	assert.strictEqual(rows[2].byClass('fi-log-msg')[0].textContent, 'retry');
	assert.strictEqual(rows[2].byClass('fi-log-count').length, 0);
	// the same message from another source is not merged
	var other = widgetWindow(function () { return {status: 200, body: {data: [
		entry('2026-10-13T13:00:59+0000', 'INFO', 'same', 31), entry('2026-10-13T13:00:58+0000', 'INFO', 'same', 32)]}}; });
	run(other.win);
	assert.strictEqual(other.root.byClass('fi-log-row').length, 2);
});

test('widget: long messages are cut at 160 characters, the title keeps more', function () {
	var t = widgetWindow(function () { return {status: 200, body: {data: [entry('2026-10-13T13:00:54+0000', 'INFO', 'y'.repeat(400), 31)]}}; });
	run(t.win);
	var msg = t.root.byClass('fi-log-msg')[0];
	assert.strictEqual(msg.textContent.length, 160);
	assert.strictEqual(msg.getAttribute('title').length, 400);
});

test('widget: running the script twice draws nothing twice and asks nothing twice', function () {
	var t = widgetWindow(function () { return {status: 200, body: {data: [entry('2026-10-13T13:00:54+0000', 'INFO', 'a', 31)]}}; });
	run(t.win);
	var rowsAfterFirst = t.root.byClass('fi-log-row').length;
	var children = t.root.children.length;
	run(t.win);
	run(t.win);
	assert.strictEqual(t.win.requests.length, 1);
	assert.strictEqual(t.root.byClass('fi-log-row').length, rowsAfterFirst);
	assert.strictEqual(t.root.children.length, children);
	assert.strictEqual(t.root.getAttribute('data-fi-ready'), '1');
});

test('widget: a root added later is picked up by the next execution only', function () {
	var t = widgetWindow(function () { return {status: 200, body: {data: []}}; });
	run(t.win);
	var late = makeRoot(t.win.document, {'data-mode': 'widget', 'data-sources': SOURCES, 'data-labels': LABELS});
	assert.strictEqual(t.win.requests.length, 1);
	run(t.win);
	assert.strictEqual(t.win.requests.length, 2);
	assert.strictEqual(late.getAttribute('data-fi-ready'), '1');
	run(t.win);
	assert.strictEqual(t.win.requests.length, 2);
});

test('widget: empty, error and no-source states say so', function () {
	var empty = widgetWindow(function () { return {status: 200, body: {data: []}}; });
	run(empty.win);
	assert.strictEqual(empty.root.byClass('fi-log-status')[0].textContent, 'No entries in 7 days');

	var failing = widgetWindow(function () { return {status: 500, body: 'oops'}; });
	run(failing.win);
	assert.strictEqual(failing.root.byClass('fi-log-status')[0].textContent, 'Failed');

	var none = widgetWindow(function () { return {status: 200, body: {data: []}}; }, []);
	run(none.win);
	assert.strictEqual(none.win.requests.length, 0);
	assert.strictEqual(none.root.byClass('fi-log-status')[0].textContent, 'No sources');
});

test('widget: broken JSON in the attributes does not throw', function () {
	var win = makeWindow(function () { return {status: 200, body: {data: []}}; });
	makeRoot(win.document, {'data-mode': 'widget', 'data-sources': '{not json', 'data-labels': '[[['});
	assert.doesNotThrow(function () { run(win); });
	assert.strictEqual(win.requests.length, 0);
});

test('widget: the standard refresh event reloads the list', function () {
	var calls = 0;
	var t = widgetWindow(function () { calls++; return {status: 200, body: {data: []}}; });
	var handlers = {};
	t.win.jQuery = function (d) {
		return {on: function (name, fn) { handlers[name] = fn; }};
	};
	run(t.win);
	assert.strictEqual(calls, 1);
	handlers['morpheus:refresh']();
	assert.strictEqual(calls, 2);
});

test('widget: when the API refuses the session the UI route is asked once per server and merged newest first', function () {
	var many = [];
	for (var i = 1; i <= 15; i++) {
		many.push({id: i, n: 'srv' + i, i: null});
	}
	var t = widgetWindow(function (req) {
		if (req.url.indexOf('/api/logs') === 0) {
			return {status: 401, body: 'login'};
		}
		var id = Number(query('?' + req.body).serverId[0]);
		return {status: 200, body: {data: [entry('2026-10-13T13:00:' + (10 + id) + '+0000', 'INFO', 'from ' + id, id)]}};
	}, many);
	t.win.document.metas = {_csrf: 'tok123', _csrf_header: 'X-CSRF-TOKEN'};
	run(t.win);
	var posts = t.win.requests.filter(function (r) { return r.method === 'POST'; });
	assert.strictEqual(posts.length, 10, 'capped at 10 servers');
	assert.strictEqual(posts[0].url, '/logs/logs.json');
	assert.strictEqual(posts[0].headers['X-CSRF-TOKEN'], 'tok123');
	var rows = t.root.byClass('fi-log-row');
	assert.strictEqual(rows.length, 10);
	assert.strictEqual(rows[0].byClass('fi-log-msg')[0].textContent, 'from 10');
	assert.strictEqual(rows[9].byClass('fi-log-msg')[0].textContent, 'from 1');
});

test('widget: when API and UI route both fail the error state shows', function () {
	var t = widgetWindow(function () { return {status: 403, body: {}}; });
	run(t.win);
	assert.strictEqual(t.root.byClass('fi-log-status')[0].textContent, 'Failed');
	assert.strictEqual(t.root.byClass('fi-log-row').length, 0);
});

// ---- history page ---------------------------------------------------------------------------

function pageWindow(router, sources, initial) {
	var win = makeWindow(router);
	var root = makeRoot(win.document, {'data-mode': 'page', 'data-sources': sources || SOURCES, 'data-labels': LABELS,
		'data-initial': initial || {}, 'data-page-size': '100'});
	return {win: win, root: root};
}

function controls(root) {
	var selects = root.byTag('select');
	var inputs = root.byTag('input');
	var levels = inputs.filter(function (i) { return i.getAttribute('type') === 'checkbox' && ['INFO', 'WARN', 'ERROR', 'DEBUG'].indexOf(i.getAttribute('value')) >= 0; });
	return {
		window: selects[0], source: selects[1], levels: levels,
		text: inputs.filter(function (i) { return i.getAttribute('type') === 'search'; })[0],
		collapse: inputs.filter(function (i) { return i.getAttribute('type') === 'checkbox' && !i.getAttribute('value'); })[0],
		more: root.byTag('button')[0], status: root.byClass('fi-log-status')[0]
	};
}

function pageOf(n, from, total) {
	var data = [];
	for (var i = 0; i < n; i++) {
		data.push(entry('2026-10-13T13:' + ('0' + (59 - ((from + i) % 60))).slice(-2) + ':00+0000', 'INFO', 'line ' + (from + i), 31));
	}
	return {success: true, total: total, data: data};
}

test('page: first load asks for 100 entries of 24 hours of all sources, newest first', function () {
	var p = pageWindow(function () { return {status: 200, body: pageOf(3, 0, 3)}; });
	run(p.win);
	var q = query(p.win.requests[0].url);
	assert.deepStrictEqual(q.servers, ['31', '32']);
	assert.deepStrictEqual([q.max, q.offset, q.sort, q.order], [['100'], ['0'], ['ts'], ['desc']]);
	assert.strictEqual(q.level, undefined, 'all four levels means no level filter');
	assert.ok(Math.abs((Date.now() - Number(q.startMs[0])) - 86400000) < 60000);
	var c = controls(p.root);
	assert.strictEqual(p.root.byClass('fi-log-row').length, 3);
	assert.strictEqual(c.status.textContent, 'Showing 3 of 3');
	assert.strictEqual(c.more.style.display, 'none');
	assert.ok(p.root.textContent.indexOf('Retention note') >= 0);
	assert.ok(p.root.textContent.indexOf('Times in UTC.') >= 0);
});

test('page: the window selector changes startMs and reloads from the top', function () {
	var p = pageWindow(function () { return {status: 200, body: pageOf(2, 0, 2)}; });
	run(p.win);
	var c = controls(p.root);
	[['1h', 3600000], ['7d', 604800000], ['30d', 2592000000]].forEach(function (pair) {
		c.window.value = pair[0];
		c.window.fire('change');
		var q = query(p.win.requests[p.win.requests.length - 1].url);
		assert.ok(Math.abs((Date.now() - Number(q.startMs[0])) - pair[1]) < 60000, pair[0]);
		assert.deepStrictEqual(q.offset, ['0']);
	});
	assert.strictEqual(p.win.requests.length, 4);
});

test('page: the level checkboxes become a pipe-separated level filter', function () {
	var p = pageWindow(function () { return {status: 200, body: pageOf(1, 0, 1)}; });
	run(p.win);
	var c = controls(p.root);
	assert.strictEqual(c.levels.length, 4);
	c.levels.filter(function (l) { return l.getAttribute('value') === 'DEBUG'; })[0].checked = false;
	c.levels.filter(function (l) { return l.getAttribute('value') === 'DEBUG'; })[0].fire('change');
	assert.deepStrictEqual(query(p.win.requests[1].url).level, ['INFO|WARN|ERROR']);
	c.levels.forEach(function (l) { l.checked = false; });
	var n = p.win.requests.length;
	c.levels[0].fire('change');
	assert.strictEqual(p.win.requests.length, n, 'no level selected asks nothing');
	assert.strictEqual(c.status.textContent, 'Pick a level');
});

test('page: the source filter sends one id taken from the embedded list', function () {
	var p = pageWindow(function () { return {status: 200, body: pageOf(1, 0, 1)}; });
	run(p.win);
	var c = controls(p.root);
	var options = c.source.children;
	assert.deepStrictEqual(options.map(function (o) { return o.getAttribute('value'); }), ['', '31', '32']);
	assert.deepStrictEqual(options.map(function (o) { return o.textContent; }), ['All sources', 'web-01', 'db-01']);
	c.source.value = '32';
	c.source.fire('change');
	assert.deepStrictEqual(query(p.win.requests[1].url).servers, ['32']);
	c.source.value = '777';
	c.source.fire('change');
	assert.deepStrictEqual(query(p.win.requests[2].url).servers, ['31', '32'], 'an id that is not embedded falls back to all');
});

test('page: the text filter works on the loaded rows and does not ask again', function () {
	var p = pageWindow(function () {
		return {status: 200, body: {total: 3, data: [
			entry('2026-10-13T13:00:03+0000', 'INFO', 'disk almost full', 31),
			entry('2026-10-13T13:00:02+0000', 'ERROR', 'connection reset', 32),
			entry('2026-10-13T13:00:01+0000', 'INFO', 'Disk cleaned', 32)]}};
	});
	run(p.win);
	var c = controls(p.root);
	var requests = p.win.requests.length;
	c.text.value = 'DISK';
	c.text.fire('input');
	assert.strictEqual(p.root.byClass('fi-log-row').length, 2);
	assert.strictEqual(p.win.requests.length, requests);
	c.text.value = 'db-01';
	c.text.fire('input');
	assert.strictEqual(p.root.byClass('fi-log-row').length, 2, 'the source name is searched too');
	c.text.value = '<script>';
	c.text.fire('input');
	assert.strictEqual(p.root.byClass('fi-log-row').length, 0);
	assert.strictEqual(c.status.textContent, 'Nothing');
	assert.ok(p.win.document.created.indexOf('SCRIPT') < 0);
});

test('page: load more appends the next 100 until the total is reached', function () {
	var p = pageWindow(function (req) {
		var offset = Number(query(req.url).offset[0]);
		return {status: 200, body: pageOf(Math.min(100, 250 - offset), offset, 250)};
	});
	run(p.win);
	var c = controls(p.root);
	assert.strictEqual(p.root.byClass('fi-log-row').length, 100);
	assert.strictEqual(c.more.style.display, '');
	assert.strictEqual(c.status.textContent, 'Showing 100 of 250');
	c.more.fire('click');
	assert.deepStrictEqual(query(p.win.requests[1].url).offset, ['100']);
	assert.strictEqual(p.root.byClass('fi-log-row').length, 200);
	c.more.fire('click');
	assert.deepStrictEqual(query(p.win.requests[2].url).offset, ['200']);
	assert.strictEqual(p.root.byClass('fi-log-row').length, 250);
	assert.strictEqual(c.more.style.display, 'none');
	assert.strictEqual(c.status.textContent, 'Showing 250 of 250');
});

test('page: collapsing repeated lines is optional and works across loaded pages', function () {
	var p = pageWindow(function () {
		return {status: 200, body: {total: 4, data: [
			entry('2026-10-13T13:00:04+0000', 'INFO', 'ping', 31), entry('2026-10-13T13:00:03+0000', 'INFO', 'ping', 31),
			entry('2026-10-13T13:00:02+0000', 'INFO', 'ping', 31), entry('2026-10-13T13:00:01+0000', 'WARN', 'ping', 31)]}};
	});
	run(p.win);
	var c = controls(p.root);
	assert.strictEqual(p.root.byClass('fi-log-row').length, 4);
	c.collapse.checked = true;
	c.collapse.fire('change');
	assert.strictEqual(p.root.byClass('fi-log-row').length, 2);
	assert.strictEqual(p.root.byClass('fi-log-count')[0].textContent, '×3');
	c.collapse.checked = false;
	c.collapse.fire('change');
	assert.strictEqual(p.root.byClass('fi-log-row').length, 4);
});

test('page: the initial state is whitelisted again in the browser', function () {
	var p = pageWindow(function () { return {status: 200, body: pageOf(1, 0, 1)}; }, SOURCES,
		{window: '7d', levels: ['WARN', 'ERROR', 'FATAL', '<b>'], source: '32', q: '  disk\n', collapse: true});
	run(p.win);
	var c = controls(p.root);
	var q = query(p.win.requests[0].url);
	assert.strictEqual(c.window.children.filter(function (o) { return o.getAttribute('selected'); })[0].getAttribute('value'), '7d');
	assert.deepStrictEqual(q.servers, ['32']);
	assert.deepStrictEqual(q.level, ['WARN|ERROR']);
	assert.strictEqual(c.text.value, 'disk');
	assert.strictEqual(c.collapse.checked, true);

	var bad = pageWindow(function () { return {status: 200, body: pageOf(1, 0, 1)}; }, SOURCES,
		{window: '90d', source: '999', levels: 'INFO', q: null});
	run(bad.win);
	var q2 = query(bad.win.requests[0].url);
	assert.deepStrictEqual(q2.servers, ['31', '32']);
	assert.strictEqual(q2.level, undefined);
});

test('page: errors and a tenant without sources are messages', function () {
	var failing = pageWindow(function () { return {status: 500, body: ''}; });
	run(failing.win);
	assert.strictEqual(controls(failing.root).status.textContent, 'Failed');

	var none = pageWindow(function () { return {status: 200, body: {data: []}}; }, []);
	run(none.win);
	assert.strictEqual(none.win.requests.length, 0);
	assert.strictEqual(controls(none.root).status.textContent, 'No sources');
});

test('page: hostile names and messages stay text in the rows and in the source filter', function () {
	var hostile = '"><img src=x onerror=alert(1)>';
	var p = pageWindow(function () {
		return {status: 200, body: {total: 1, data: [entry('2026-10-13T13:00:01+0000', 'ERROR', hostile + '\u001b[1m', 31)]}};
	}, [{id: 31, n: hostile, i: 410}]);
	run(p.win);
	assert.ok(p.win.document.created.indexOf('IMG') < 0);
	assert.strictEqual(p.root.byClass('fi-log-msg')[0].textContent, hostile);
	assert.strictEqual(controls(p.root).source.children[1].textContent, hostile);
});

test('an unusable response (success false) counts as a failure and triggers the fallback', function () {
	var t = widgetWindow(function (req) {
		if (req.method === 'GET') {
			return {status: 200, body: {success: false}};
		}
		return {status: 200, body: {data: [entry('2026-10-13T13:00:01+0000', 'INFO', 'via ui', 31)]}};
	});
	run(t.win);
	assert.strictEqual(t.root.byClass('fi-log-row').length, 1);
	assert.strictEqual(t.root.byClass('fi-log-msg')[0].textContent, 'via ui');
});

console.log(passed + ' passed');
if (process.exitCode) {
	console.log('FAILED');
}
