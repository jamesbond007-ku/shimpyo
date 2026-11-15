// 웹 시연판의 실제 함수로 늦은 제어·주차 신호 소실을 재현한다. 실행: node tools/web-safety-test.cjs
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(require('node:path').join(__dirname, '../web/shimpyo.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script); // 전체 자바스크립트 문법도 확인한다.
function source(name) {
  const start = script.search(new RegExp(`(?:async )?function ${name}\\(`));
  assert(start >= 0, name);
  return script.slice(start, script.indexOf('\n}', start) + 2);
}
function setup() {
  const pending = [];
  const lines = [];
  const ctx = vm.createContext({
    S: { phase: 'RESTING', parked: true, car: { seat: false, window: false }, naviAvailable: true },
    session: 1, curRun: 1, busy: false, plannedRestAt: null, restStartMs: 0, simClock: 0, ventCloseAt: 0,
    finishingWake: false, nextOfferAt: 0,
    say: async t => { lines.push(t); }, log() {}, render() {}, carLog() {}, advanceClock() {}, now: () => 1,
    Voice: { stop() {}, pending: null }, sleep: () => new Promise(r => pending.push(r)),
    driveMin: () => 5,
  });
  vm.runInContext('function setPhase(p) { S.phase = p; } function run(fn) { return fn(); }', ctx);
  for (const name of ['control', 'beginRest', 'startWake', 'endRestByDriving', 'restoreCabin', 'checkNaviAvailable']) vm.runInContext(source(name), ctx);
  return { ctx, lines, pending };
}
(async () => {
  {
    const { ctx, pending } = setup();
    const p = ctx.control('시험', () => {});
    ctx.session++; ctx.curRun = ctx.session;
    pending.shift()();
    await assert.rejects(p, /cancel/);
  }
  {
    const { ctx, pending } = setup();
    const p = ctx.beginRest();
    await new Promise(setImmediate);
    assert.equal(pending.length, 1);
    ctx.S.parked = false; ctx.endRestByDriving(); ctx.curRun = ctx.session;
    pending.shift()();
    await assert.rejects(p, /cancel/);
    assert.equal(ctx.S.car.seat, false);
    assert.equal(ctx.S.car.restClimate, false);
  }
  {
    const { ctx, pending } = setup();
    ctx.S.car.seat = true;
    const p = ctx.startWake();
    await new Promise(setImmediate);
    ctx.S.parked = false;
    pending.shift()();
    await p;
    assert.equal(ctx.S.car.window, false);
    assert.equal(ctx.S.phase, 'MONITORING');
  }
  {
    const { ctx, lines } = setup();
    ctx.S.parked = null; ctx.S.car.seat = true; ctx.S.car.window = true;
    ctx.endRestByDriving();
    assert.equal(ctx.S.car.seat, false); assert.equal(ctx.S.car.window, false);
    assert(lines[0].includes('주차 상태를 확인할 수 없어'));
  }
  {
    const { ctx } = setup();
    ctx.S.parked = null;
    await ctx.beginRest();
    assert.equal(ctx.S.phase, 'WAITING_PARK');
    assert.equal(ctx.S.car.seat, false);
  }
  {
    const { ctx, lines } = setup();
    ctx.S.naviAvailable = false;
    assert.equal(await ctx.checkNaviAvailable(), false);
    assert.equal(ctx.S.phase, 'MONITORING');
    assert.equal(ctx.nextOfferAt, 15);
    assert(lines[0].includes('안내를 시작하지 못했어요'));
  }
  console.log('웹 안전 회귀 6개 통과');
})().catch(e => { console.error(e); process.exitCode = 1; });
