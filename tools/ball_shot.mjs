#!/usr/bin/env node
/*
 * 把 reports/ball-cutie-lab.html 里的那颗球渲染成 PNG。
 *
 * 为什么是"驱动那一页"而不是"照抄一份":
 *   照抄一份渲染器就等于在这个仓库里再放一套会和 Kotlin 漂移的代码 ——
 *   这个项目已经有一套 check_look_sync.py 在专门盯这件事。这里的做法是
 *   用 CDP 打开实验室页面本身,然后在那页里 `new LAB.Ball(...)` 建一颗
 *   自己的球、渲染、截图。渲染路径 100% 是实验室那一份,没有第二实现。
 *
 * ── 这台机器上 headless WebGL 是能跑的 ──
 * 以前的结论是"本机跑不了 headless Chrome",那是被 --disable-gpu 带偏的:
 * 加了它,ANGLE 连软件后端都起不来,页面里 getContext('webgl') 直接返回 null。
 * 去掉这个开关之后走的是真的 GPU(实测 ANGLE Metal / Apple M4 Pro)。
 * 所以**不要**再加 --disable-gpu。
 *
 * ── 两件不猜的事 ──
 * 1. **不自己合成**。WebGL 画布的 alpha 到底是预乘还是直通,取决于画布的
 *    premultipliedAlpha 与页面的混合方式,算起来容易错。所以交给浏览器:
 *     * 透明版:Emulation 把默认背景设成全透明,截出来天然是直通 alpha 的 PNG;
 *     * 实底版:把底色画在页面自己身上,截出来是合成好的不透明图。
 *    两张图必须自洽 —— 把透明版按 over 叠到同一个底色上,应当逐像素等于实底版。
 *    这个等式就是自检,见 --verify。
 * 2. **不假设画布还在**。WebGL 上下文默认 preserveDrawingBuffer:false,合成
 *    一次之后缓冲就可能被清掉。所以定格帧挂在一个常驻 rAF 里每帧重画,
 *    截图那一刻画布上一定有东西。
 *
 * ── 定格 ──
 * 图标要的是"某一刻的样子",而这一页是活的:呼吸、左右摇摆、眨眼、随机小动作
 * 都会改变画面。所以先空跑若干秒让表情插值到稳定态,再把**动态**按住:
 * 呼吸相位归 1.0、摆动归零、不眨眼、不随机动。表情自己的形状(LISTENING 的
 * 歪头、眼睛开合、张嘴)全部留着 —— 那是这个表情本身。
 *
 * 用法:
 *   node tools/ball_shot.mjs --look cream --mood LISTENING --size 864 --out /tmp/a.png
 *   node tools/ball_shot.mjs --look cream --mood LISTENING --size 864 --probe
 *   node tools/ball_shot.mjs --look cream --mood LISTENING --size 864 --no-ears
 *
 * 参数:
 *   --look    实验室预设 key(now/jelly/kitty/mochi/sticker/sumikko/cream/imp)
 *   --mood    FACE_NEW 里的表情名,默认 LISTENING
 *   --size    CSS 边长(像素)= 输出图边长,默认 864
 *   --settle  定格的空跑秒数,默认 6
 *   --no-ears 把 earH 设成 0,用来比较有无耳朵的读法
 *   --bg      R,G,B 十进制(0-255)给实底版图用,默认不出的 14,23,43
 *   --probe   只打印诊断,不写文件
 *   --alpha   同时写一张 .alpha.png(透明底直通 alpha)
 *   --verify  只做自检:透明版叠底色 vs 实底版,逐像素比
 */
import { spawn } from 'node:child_process';
import { writeFileSync, existsSync, readdirSync, mkdtempSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const LAB = join(REPO, 'reports', 'ball-cutie-lab.html');

/* ---------- 参数 ---------- */
const argv = process.argv.slice(2);
function arg(name, dflt) {
  const i = argv.indexOf('--' + name);
  if (i < 0) return dflt;
  const v = argv[i + 1];
  return v === undefined || v.startsWith('--') ? true : v;
}
const LOOK = String(arg('look', 'cream'));
const MOOD = String(arg('mood', 'LISTENING'));
const SIZE = parseInt(arg('size', '864'), 10);
const SETTLE = parseFloat(arg('settle', '6'));
const NO_EARS = !!arg('no-ears', false);
const PROBE = !!arg('probe', false);
const WITH_ALPHA = !!arg('alpha', false);
const VERIFY = !!arg('verify', false);
const OUT = arg('out', null);
const BG = String(arg('bg', '14,23,43')).split(',').map((s) => parseInt(s, 10));
if (BG.length !== 3 || BG.some((v) => !Number.isFinite(v))) {
  console.error('--bg 要给三个 0-255 的数,比如 --bg 14,23,43');
  process.exit(2);
}

if (!existsSync(LAB)) {
  console.error('找不到实验室页面:', LAB);
  process.exit(2);
}

/* ---------- 找 Chromium ----------
 * 不写死版本号:playwright 的缓存目录里可能有多个版本。环境变量
 * CHROME_PATH 优先,方便换别的构建。 */
function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const roots = [
    join(process.env.HOME || '', 'Library/Caches/ms-playwright'),
    join(process.env.HOME || '', '.cache/ms-playwright'),
  ];
  const rels = [
    'chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
    'chrome-mac/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
    'chrome-linux/chrome',
  ];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    const vers = readdirSync(root).filter((d) => d.startsWith('chromium-')).sort().reverse();
    for (const v of vers)
      for (const rel of rels) {
        const p = join(root, v, rel);
        if (existsSync(p)) return p;
      }
  }
  return null;
}

const CHROME = findChrome();
if (!CHROME) {
  console.error('没找到 Chromium。设 CHROME_PATH 指到可执行文件。');
  process.exit(2);
}

/* ---------- 起浏览器 ---------- */
const PORT = 9000 + Math.floor(Math.random() * 900);
const profile = mkdtempSync(join(tmpdir(), 'ballshot-'));
const chrome = spawn(
  CHROME,
  [
    '--headless',
    // 千万不要加 --disable-gpu:加了它 getContext('webgl') 就返回 null。
    '--no-sandbox',
    '--hide-scrollbars',
    '--mute-audio',
    '--disable-extensions',
    '--disable-background-timer-throttling',
    '--allow-file-access-from-files',
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + profile,
    '--window-size=1920,1920',
    'about:blank',
  ],
  { stdio: ['ignore', 'ignore', 'pipe'] }
);
let chromeErr = '';
chrome.stderr.on('data', (d) => { chromeErr += d.toString(); });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitDevtools() {
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (r.ok) return;
    } catch (e) { /* 还没起来 */ }
    await sleep(150);
  }
  throw new Error('调试端口没起来。chrome stderr:\n' + chromeErr.slice(-2000));
}

let ws, msgId = 0;
const pending = new Map();
function send(method, params = {}) {
  return new Promise((res, rej) => {
    const id = ++msgId;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params }));
  });
}

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', {
    expression, returnByValue: true, awaitPromise: true, timeout: 300000,
  });
  const res = r.result || {};
  if (res.exceptionDetails) {
    throw new Error('页面里抛异常: ' + (res.exceptionDetails.exception?.description || res.exceptionDetails.text));
  }
  return res.result?.value;
}

/* ==========================================================================
   注入页面的那一段:建球、定格、量几何
   ========================================================================== */
function pageScript() {
  return `(() => {
  const T = ${SIZE}, MOOD = ${JSON.stringify(MOOD)}, LOOK = ${JSON.stringify(LOOK)};
  const SETTLE = ${SETTLE}, NO_EARS = ${NO_EARS};
  if (!window.__ready) return {ok:false, why:'__ready 还是 false',
    err:(document.getElementById('err')||{}).textContent||''};
  const LAB = window.LAB;
  const preset = LAB.PRESETS.find(p => p.key === LOOK);
  if (!preset) return {ok:false, why:'没有这个预设: '+LOOK+' 可选 '+LAB.PRESETS.map(p=>p.key).join('/')};

  // 参数取法和页面滑杆一致(都走 preset 结构),deep clone 免得改动倒灌回页面。
  const params = JSON.parse(JSON.stringify(preset));
  if (NO_EARS) params.earH = 0;
  if (!params.faces[MOOD]) return {ok:false, why:'faces 里没有这个表情: '+MOOD};

  // 画布必须挂进 DOM —— resize() 读 clientWidth。CSS 尺寸给 T,再把
  // devicePixelRatio 钉成 1,于是 canvas.width 正好 === T,不依赖机器 DPR。
  const wrap = document.createElement('div');
  wrap.id = '__shotwrap';
  wrap.style.cssText = 'position:fixed;left:0;top:0;width:'+T+'px;height:'+T+'px;background:transparent;z-index:1';
  const cv = document.createElement('canvas');
  cv.style.cssText = 'display:block;width:'+T+'px;height:'+T+'px';
  wrap.appendChild(cv);
  document.body.appendChild(wrap);
  const dpr0 = Object.getOwnPropertyDescriptor(window, 'devicePixelRatio');
  Object.defineProperty(window, 'devicePixelRatio', {value: 1, configurable: true});
  // 页面上别的 canvas 会碍事的地方:让 body 不留边距,免得整体位移
  document.documentElement.style.margin = '0'; document.body.style.margin = '0';

  // 把实验室自己的 UI 藏掉。它在整个视口上铺了一整页预设格子,截图会把它
  // 一起拍进去 —— 第一次跑就是这么错的(差异区域一直延伸到画布右下角)。
  // 用 visibility 而不是 display:布局不动,各画布的 clientWidth 还是原值。
  for (const el of [...document.body.children]) {
    if (el !== wrap) el.style.visibility = 'hidden';
  }

  let ball;
  try { ball = new LAB.Ball(cv, params); }
  catch (e) { return {ok:false, why:'Ball 构造失败: '+(e && e.message || e)}; }
  ball.autoFidget = false;
  ball.mood = MOOD;
  ball.resize();
  if (cv.width !== T || cv.height !== T) {
    return {ok:false, why:'画布尺寸不是 '+T+',而是 '+cv.width+'x'+cv.height+' —— devicePixelRatio 没被钉住'};
  }

  // ---- 1. 空跑,把表情插值到稳定态 ----
  const DT = 1/120;
  for (let i = 0; i < Math.round(SETTLE / DT); i++) ball.frame(DT, i * DT);

  // ---- 2. 按住动态 ----
  // 留:表情自己的形状(歪头 rotZ、眼睛开合、张嘴、腮红、由表情定的身体色)。
  // 去:一切"此刻相位"—— 呼吸、缓慢摆动、眨眼、随机小动作、视线漂移。
  //     图标是一张静止的图,留着这些只会让两次渲染不一样。
  const st = ball.st, orig = st.tick.bind(st);
  st.blinkLeft = 0; st.blinkNext = 1e9; st.action = 'NONE';
  st.tick = function (dt, t, mood, p) {
    orig(dt, t, mood, p);
    this.rotX = 0; this.rotY = 0;       // 缓慢摆动 -> 正对镜头
    this.hopY = 0;
    this.breathe = 1;                   // 呼吸相位 -> 静息尺寸
    this.scaleX = 1; this.scaleY = 1;
    this.eyeOffX = 0; this.eyeOffY = 0; // 视线漂移(Listening 的 think=0,本就是 0)
    this.lid = 1;                       // 眼皮全开
  };

  // ---- 3. 常驻 rAF 每帧重画 ----
  // 不这么做的话,preserveDrawingBuffer:false 会让缓冲在合成后被清掉,
  // 截图那一刻画布上是空的。
  const frozen = () => { ball.frame(0, 0); requestAnimationFrame(frozen); };
  requestAnimationFrame(frozen);

  window.__shot = {ball, cv, wrap, params, LAB,
                   restoreDpr: () => { if (dpr0) Object.defineProperty(window,'devicePixelRatio',dpr0); }};

  // ---- 4. 量几何(在页面里量,免得再把 1700 万像素搬过 CDP)----
  const m = {look:LOOK, mood:MOOD, size:T, ears:params.earH>0,
             eyeRadius:params.eyeR, eyeCyl:params.eyeH, faceZ:params.faceZ,
             bodyScale:params.bodyScale};
  return {ok:true, info:m};
})()`;
}

/* 在页面里量 alpha 包围盒 / 最大半径 / 采样色。单独一步,因为要等 rAF 画完。 */
function measureScript(bg) {
  return `(() => {
  const s = window.__shot, T = s.cv.width;
  const gl = s.ball.gl;
  const px = new Uint8Array(T*T*4);
  s.ball.frame(0,0);                       // 保证刚画完,缓冲有效
  gl.readPixels(0,0,T,T,gl.RGBA,gl.UNSIGNED_BYTE,px);
  let minX=T,minY=T,maxX=-1,maxY=-1,maxR2=-1,n=0;
  const cx=T/2, cy=T/2;
  const at = (x,y) => { const i=((T-1-y)*T+x)*4; return [px[i],px[i+1],px[i+2],px[i+3]]; };
  for (let y=0;y<T;y++) for (let x=0;x<T;x++){
    const i=((T-1-y)*T+x)*4, a=px[i+3];
    if(a>8){ n++;
      if(x<minX)minX=x; if(x>maxX)maxX=x; if(y<minY)minY=y; if(y>maxY)maxY=y;
      const dx=x+0.5-cx, dy=y+0.5-cy, r2=dx*dx+dy*dy; if(r2>maxR2)maxR2=r2;
    }
  }
  // 采样:身体正中(避开五官)、以及过球心水平线上一串,给"到底偏不偏蓝"一个数
  const body = at(Math.round(cx - T*0.16), Math.round(cy - T*0.28));
  return {bbox:[minX,minY,maxX,maxY], w:maxX-minX+1, h:maxY-minY+1,
          maxRadius:+Math.sqrt(maxR2).toFixed(3), pixels:n, bodySample:body,
          bg:${JSON.stringify(bg)}, canvasW:T};
})()`;
}

async function captureTransparent() {
  await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
  await evaluate(`window.__shot.wrap.style.background='transparent'; document.body.style.background='transparent';`);
  await sleep(120);
  const r = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: SIZE, height: SIZE, scale: 1 },
  });
  return Buffer.from(r.result.data, 'base64');
}

async function captureOverBg(bg) {
  await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
  await evaluate(`document.body.style.background='rgb(${bg[0]},${bg[1]},${bg[2]})'; window.__shot.wrap.style.background='rgb(${bg[0]},${bg[1]},${bg[2]})';`);
  await sleep(120);
  const r = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: SIZE, height: SIZE, scale: 1 },
  });
  return Buffer.from(r.result.data, 'base64');
}

async function main() {
  await waitDevtools();
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const target = list.find((t) => t.type === 'page');
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const { res, rej } = pending.get(m.id);
      pending.delete(m.id);
      if (m.error) rej(new Error(JSON.stringify(m.error))); else res(m);
    }
  };

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: 'file://' + LAB });

  let ready = false;
  for (let i = 0; i < 300; i++) {
    await sleep(100);
    try { if (await evaluate('!!window.__ready')) { ready = true; break; } } catch (e) { /* 导航中 */ }
  }
  if (!ready) {
    const err = await evaluate("(document.getElementById('err')||{}).textContent||'(没有 #err)'").catch(() => '?');
    throw new Error('实验室页面没就绪。页面里的报错: ' + err);
  }

  const built = await evaluate(pageScript());
  if (!built?.ok) throw new Error('渲染失败: ' + JSON.stringify(built));
  await sleep(400); // 让常驻 rAF 至少跑过一帧

  const m = { ...built.info, ...(await evaluate(measureScript(BG))) };
  console.log(`预设 ${m.look} / 表情 ${m.mood} / ${m.size}px / 耳朵:${m.ears ? '有' : '无'}`);
  console.log(`  包围盒 x[${m.bbox[0]},${m.bbox[2]}] y[${m.bbox[1]},${m.bbox[3]}]  宽 ${m.w} 高 ${m.h}`);
  console.log(`  最大半径 ${m.maxRadius}px (画布半宽 ${m.size / 2}, 占 ${(m.maxRadius / (m.size / 2) * 100).toFixed(1)}%)`);
  console.log(`  非透明像素 ${m.pixels}  占全画布 ${(m.pixels / (m.size * m.size) * 100).toFixed(1)}%`);
  console.log(`  身体采样色 rgba(${m.bodySample.join(',')})`);

  if (PROBE) return;

  const transparent = await captureTransparent();
  const overBg = await captureOverBg(BG);

  // 先落盘再自检 —— 自检失败不该把已经渲好的图一起吞掉(第一版就是那样,
  // 报了个"对不上"结果两张图都没写成,连看着判断的机会都没有)。
  const base = OUT || join(tmpdir(), `ball-${LOOK}-${MOOD}.png`);
  const opaquePath = base.replace(/\.png$/, '') + '.png';
  const alphaPath = base.replace(/\.png$/, '') + '.alpha.png';
  writeFileSync(opaquePath, overBg);
  console.log(`  写出 ${opaquePath}  (${(overBg.length / 1024).toFixed(0)} KiB)`);
  if (WITH_ALPHA) writeFileSync(alphaPath, transparent);
  if (WITH_ALPHA) console.log(`  写出 ${alphaPath}  (${(transparent.length / 1024).toFixed(0)} KiB)`);

  if (VERIFY) {
    const { execFileSync } = await import('node:child_process');
    const tmpA = join(tmpdir(), 'ballshot-t.png'), tmpB = join(tmpdir(), 'ballshot-b.png');
    writeFileSync(tmpA, transparent); writeFileSync(tmpB, overBg);
    // Needs Pillow. Point PY at an interpreter that has it if `python3` does not.
    const py = process.env.PY || 'python3';
    try {
      const out = execFileSync(py, [join(HERE, 'ball_shot_verify.py'), tmpA, tmpB,
        String(BG[0]), String(BG[1]), String(BG[2])], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
      console.log(out.trimEnd());
    } catch (e) {
      if (e.stdout) process.stdout.write(e.stdout);
      if (e.stderr) process.stderr.write(e.stderr);
      process.exitCode = 1;
    }
  }
}

try {
  await main();
} catch (e) {
  console.error('✗ ' + e.message);
  process.exitCode = 1;
} finally {
  try { await evaluate('window.__shot && window.__shot.restoreDpr && window.__shot.restoreDpr()'); } catch (e) {}
  try { ws && ws.close(); } catch (e) {}
  chrome.kill('SIGKILL');
}
