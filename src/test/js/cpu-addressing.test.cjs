const test = require('node:test');
const assert = require('node:assert/strict');
const { terminal } = require('./terminal-fixture.cjs');

for (const page of ['../../main/resources/assets/webpage.html', '../../../example_website/index.php']) {
test(page + ': duplicate CPU names remain separate and requests use stable IDs', () => {
    const { context, requests, elements } = terminal(page);
    const first = 'ae2:minecraft:overworld:1:2:3';
    const second = 'ae2:minecraft:overworld:4:5:6';
    context.globalCPUList = {
        [first]: { name: 'Main <CPU>', isBusy: false, availableStorage: 64 },
        [second]: { name: 'Main <CPU>', isBusy: false, availableStorage: 128 }
    };
    context.displayCPUList();
    const rendered = elements.get('terminalCPUList').innerHTML;
    assert.equal((rendered.match(/<button/g) || []).length, 2);
    const labels = html => [...html.matchAll(/<button\b[^>]*>([^<]*)/g)].map(match => match[1]);
    assert.deepEqual(labels(rendered), ['Main &lt;CPU&gt;', 'Main &lt;CPU&gt;']);
    context.currentJob.bytesTotal = 16;
    context.updateCPUListForJob();
    assert.deepEqual(labels(elements.get('terminalCPUListForJob').innerHTML), ['Main &lt;CPU&gt;', 'Main &lt;CPU&gt;']);
    assert.ok(rendered.includes('Main &lt;CPU&gt;'));
    assert.ok(!rendered.includes('Main <CPU>'));
    context.selectCPU({ name: second });
    assert.equal(new URL(requests[0].url, 'http://local/').searchParams.get('cpu'), second);
    assert.ok(!elements.get('overlaytext').innerHTML.includes('Main <CPU>'));
    requests[0].success({ status: 'OK', data: { isBusy: false } });
    assert.ok(elements.get('terminalCPUHeaderText').innerHTML.includes('Main &lt;CPU&gt;'));
    assert.ok(!elements.get('terminalCPUHeaderText').innerHTML.includes(second));
});

test(page + ': CPU selection survives reorder and rename but never switches after removal', () => {
    const { context, requests } = terminal(page);
    const first = 'ae2:0:1:2:3';
    const second = 'ae2:0:4:5:6';
    const cpu = name => ({ name, isBusy: false, availableStorage: 64 });
    context.globalCPUList = { [first]: cpu('Same'), [second]: cpu('Same') };
    context.selectedCPU = first;
    context.updateCPUList();
    requests.shift().success({ status: 'OK', data: { [second]: cpu('Same'), [first]: cpu('Renamed') } });
    assert.equal(context.selectedCPU, first);
    context.updateCPUList();
    requests.shift().success({ status: 'OK', data: { [second]: cpu('Same') } });
    assert.ok(!context.selectedCPU);
    context.cancelJobOnCPU(context.selectedCPU);
    assert.equal(requests.length, 0);
});

test(page + ': vanished order target requires selection instead of silently choosing another CPU', () => {
    const { context, requests } = terminal(page);
    const first = 'ae2:0:1:2:3';
    const second = 'ae2:0:4:5:6';
    context.currentJob.bytesTotal = 16;
    context.currentJob.id = 12;
    context.currentWindow = 2;
    context.globalCPUList = { [second]: { name: 'Same', isBusy: false, availableStorage: 64 } };
    context.cpuForJob = first;
    context.updateCPUListForJob();
    context.startCurrentJob();
    assert.equal(requests.length, 0);
    context.selectCPUForJob({ name: second });
    context.startCurrentJob();
    assert.equal(new URL(requests[0].url, 'http://local/').searchParams.get('cpu'), second);
});

test(page + ': stale CPU detail response cannot overwrite a newer selection', () => {
    const { context, requests, elements } = terminal(page);
    const first = 'ae2:0:1:2:3';
    const second = 'ae2:0:4:5:6';
    context.globalCPUList = { [first]: { name: 'First' }, [second]: { name: 'Second' } };
    context.selectCPU({ name: first });
    context.selectCPU({ name: second });
    requests[1].success({ status: 'OK', data: { items: [], finalOutput: { itemname: 'Second job', quantity: 2 } } });
    const newer = elements.get('terminalCPUHeaderText').innerHTML;
    requests[0].success({ status: 'OK', data: { isBusy: false } });
    assert.equal(elements.get('terminalCPUHeaderText').innerHTML, newer);
    assert.equal(context.loadingMessages.length, 0);
});

test(page + ': rejected stale CPU submit retains the plan and refreshes without resubmitting', () => {
    const { context, requests } = terminal(page);
    const id = 'ae2:0:1:2:3';
    context.currentWindow = 2;
    context.currentJob.id = 12;
    context.currentJob.bytesTotal = 16;
    context.cpuForJob = id;
    context.globalCPUList = { [id]: { name: 'Main', isBusy: false, availableStorage: 64 } };
    context.startCurrentJob();
    requests.shift().success({ status: 'CPU_NOT_FOUND' });
    assert.equal(context.currentWindow, 2);
    assert.equal(context.currentJob.id, 12);
    assert.ok(!context.cpuForJob);
    const refresh = requests.find(request => new URL(request.url, 'http://local/').pathname === '/list');
    assert.ok(refresh);
    refresh.success({ status: 'OK', data: { 'ae2:0:4:5:6': { name: 'Main', isBusy: false, availableStorage: 64 } } });
    const sent = requests.length;
    context.startCurrentJob();
    assert.equal(requests.length, sent);
    assert.equal(context.loadingMessages.length, 0);
});

test(page + ': opening CPU view after removal clears the previous screen', () => {
    const { context, elements } = terminal(page);
    context.selectedCPU = null;
    context.document.getElementById('terminalcontent').innerHTML = 'previous item list';
    context.openCraftingStatus();
    assert.equal(elements.get('terminalcontent').innerHTML, '');
    assert.equal(elements.get('cancelJobOnCPUButton').style.display, 'none');
});

test(page + ': a fresh plan waits for its size before choosing a default CPU', () => {
    const { context, requests } = terminal(page);
    context.currentJob.bytesTotal = 16;
    context.beginOrderingItem('AAAAAAAAAAAAAAAAAAAAAA');
    requests.shift().success({ status: 'OK', data: { jobID: 13 } });
    context.updateCPUList();
    requests.shift().success({ status: 'OK', data: {
        'ae2:0:1:2:3': { name: 'Small', isBusy: false, availableStorage: 64 },
        'ae2:0:4:5:6': { name: 'Large', isBusy: false, availableStorage: 256 }
    } });
    context.updateCraftingPlan();
    requests.shift().success({ status: 'OK', data: { isDone: true, isSimulating: false, bytesTotal: 128, plan: [] } });
    assert.equal(context.cpuForJob, 'ae2:0:4:5:6');
});

}
