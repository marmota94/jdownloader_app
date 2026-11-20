// Executes the actual injected bridge script against browser API doubles.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const kotlin = fs.readFileSync(path.join(__dirname, '../app/src/main/java/org/jdownloader/remote/presentation/CnlBrowserScreen.kt'), 'utf8');
const script = kotlin.match(/private val interception = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const calls = [], events = [], forms = [];
class FakeFormData { constructor(form) { this.values = form?.values || []; } forEach(fn) { this.values.forEach(([k,v]) => fn(v,k)); } }
class Form { constructor(action, values) { this.action = action; this.method = 'post'; this.values = values; } submit() { this.nativeSubmitted = true; } }
class Xhr { open(m,u) { this.originalUrl = u; } send() { this.nativeSent = true; } dispatchEvent(e) { events.push(e.type); } }
let listener;
const sandbox = {
    URL, URLSearchParams, FormData: FakeFormData, HTMLFormElement: Form, XMLHttpRequest: Xhr,
    Response, Request, Event, location: { href: 'https://example.org/download' }, setTimeout, clearTimeout,
    CustomEvent: class extends Event { constructor(type, opts){super(type);this.detail=opts.detail;} },
    dispatchEvent(event){ forms.push(event.detail); },
    document: { addEventListener(type, fn) { assert.equal(type, 'submit'); listener = fn; } },
    fetch: async function(url) { return new Response('remote:' + url); },
    JDCnl: { request(id, url, method, body) {
        calls.push({id,url,method,body});
        setTimeout(() => sandbox.__jdCnlReply(id, {status: url.includes('failure') ? 400 : 200, body: url.includes('failure') ? 'failed' : 'success\r\n'}), 5);
    } },
};
sandbox.window = sandbox;
vm.runInNewContext(script, sandbox);
(async () => {
    let resolved = false;
    const pendingResult = sandbox.fetch('http://127.0.0.1:9666/flash/addcrypted2', {method:'POST', body:new URLSearchParams({crypted:'ab+cd/ef=', jk:"function f(){return 'key';}"})}).then(r => {resolved=true;return r;});
    assert.equal(resolved, false, 'Native request must return without waiting for backend completion');
    const result = await pendingResult;
    assert.equal(await result.text(), 'success\r\n');
    assert.match(calls[0].body, /crypted=ab%2Bcd%2Fef%3D/);
    assert.equal(calls[0].method, 'POST');
    const remote = await sandbox.fetch('https://example.org/data');
    assert.equal(await remote.text(), 'remote:https://example.org/data');
    assert.equal(calls.length, 1);
    await sandbox.fetch('http://127.0.0.1:9667/flash/add');
    assert.equal(calls.length, 1);
    const form = new Form('http://localhost:9666/flash/add', [['urls','https://example.org/a'],['passwords','pä&ss']]);
    form.submit();
    assert.equal(form.nativeSubmitted, undefined);
    assert.equal(new URLSearchParams(calls[1].body).get('passwords'), 'pä&ss');
    let prevented = false;
    listener({target:form, preventDefault(){prevented=true}, stopImmediatePropagation(){}});
    assert.equal(prevented, true);
    const xhr = new Xhr(); xhr.open('POST','http://127.0.0.1:9666/flash/add'); xhr.send('urls=example');
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(xhr.status, 200); assert.equal(xhr.readyState, 4); assert.equal(xhr.responseText, 'success\r\n');
    assert.deepEqual(events, ['readystatechange','load','loadend']);
    assert.equal(forms.length, 2);
    assert.equal(forms[0].status, 200);
    const failed = await sandbox.fetch('http://127.0.0.1:9666/failure', {method:'POST',body:'urls=x'});
    assert.equal(failed.status, 400);
    assert.equal(await failed.text(), 'failed');
    console.log('CNL browser bridge: 8 scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
