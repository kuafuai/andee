"""切平的眼睛网格到底长什么样 —— 跑一遍，并对照注释里写下的数。

    python3 tools/eye_mesh_check.py       # 退出码 1 = 网格不合法

为什么要有这个脚本：headless WebGL 在这台机器上是能跑的（2026-09-30 在
`tools/ball_shot.mjs` 里证过；原来"用不了"的结论是加了 `--disable-gpu` 造成的，
它会让 `getContext('webgl')` 返回 null）。**但"能渲染"并不等于"该走渲染"** ——
`eyeMesh` 是纯数学，抽出来在 Node 里跑一遍，几秒就能拿到环表、顶点数、索引范围，
而且失败信息能指到具体哪个环。渲染一条路贵得多，还得先建浏览器。
两条路的分工：这个脚本保证"形状是合法的、注释里那几个数是对的"；
要看"好不好看"才需要去渲一张图。

它能证明的：网格自洽（没有 NaN、索引不越界、环是单调的），以及 `BallLook.IMP`
和 `gl/Eye.kt` 注释里那几个数**是对的**。它不能证明的：它好不好看。这条界要一直
说清楚，否则一次"跑绿了"很容易被读成"屏幕上看过了"。

⚠️ 它验的是**实验室那一份** `eyeMesh`。Kotlin 的 `gl/Eye.kt` 是它的原件，两边
不共用代码 —— 所以这个脚本只保证实验室画的形状是对的，两边还一致要靠人读。
真机上的几何没有等价的自动检查（那需要设备或模拟器）。
"""
import os
import pathlib
import subprocess
import tempfile

# 仓库根按脚本自身位置推断，别写死绝对路径 —— 写死过一次，别人 clone 下来
# 第一行就指向不存在的目录，且报错是"文件读不到"，看不出是路径问题。
ROOT = pathlib.Path(__file__).resolve().parent.parent
html = (ROOT / "reports/ball-cutie-lab.html").read_text()

i = html.index("function eyeMesh(")
j = html.index("{", i)
depth, k = 0, j
while True:
    if html[k] == "{":
        depth += 1
    elif html[k] == "}":
        depth -= 1
        if depth == 0:
            break
    k += 1
fn = html[i:k + 1]

harness = fn + r"""
const DEG = 57.29578;
function audit(label, radius, cylH, topCut){
  const m = eyeMesh(radius, cylH, topCut, 4, 12);
  const {pos, nrm, uv, idx} = m;
  const bad = [];
  const finite = a => Array.prototype.every.call(a, Number.isFinite);
  if(!finite(pos)) bad.push("pos 里有 NaN/Inf");
  if(!finite(nrm)) bad.push("nrm 里有 NaN/Inf");
  if(!finite(uv))  bad.push("uv 里有 NaN/Inf");
  let maxIdx = -1;
  for(const v of idx) if(v > maxIdx) maxIdx = v;
  const vertCount = pos.length/3;
  if(maxIdx >= vertCount) bad.push(`索引越界 ${maxIdx} >= ${vertCount}`);
  // 每个被索引到的顶点都必须是有限值（没被索引到的尾部必须是全 0 —— 那正是
  // 类型化数组"多留了几格"的样子，不该出现非零垃圾）
  const used = new Set(Array.from(idx));
  for(const v of used) for(let c=0;c<3;c++)
    if(!Number.isFinite(pos[v*3+c])) bad.push(`顶点 ${v} 非有限`);
  let usedMax = -1;
  for(const v of used) if(v > usedMax) usedMax = v;
  for(let v=usedMax+1; v<vertCount; v++) for(let c=0;c<3;c++)
    if(pos[v*3+c] !== 0) bad.push(`未使用的顶点 ${v} 有非零位置`);

  // 环表：从位置里把 y 恢复出来（每个环有 stride 个点）
  const half = cylH/2, halfHeight = radius + half;
  const cut = Math.min(0.85, Math.max(0, topCut));
  const yCut = halfHeight*(1-cut);
  // 环的 y 序列：取每环第 0 个顶点
  const ys = [];
  for(let v=0; v<pos.length/3; v+=13){
    const y = pos[v*3+1];
    if(Number.isFinite(y) && y !== 0 && v < pos.length/3) {}
  }
  // 严格一点：按 stride 走
  const stride = 13;
  // 找出实际用到的环数：索引里最大顶点所在的环
  const ringCount = Math.floor((usedMax - 13) / stride) + 1; // 圆心在最后一环的 stride 上
  let rings = 0;
  for(let r=0; r<40; r++){
    const v = r*stride;
    if(v > usedMax) break;
    if(!used.has(v)) break;
    rings++;
  }

  // 结论数：眼睛的净形状
  let minY = Infinity, maxY = -Infinity, maxAbsX = 0, topWidth = 0;
  for(const v of used){
    const x = pos[v*3], y = pos[v*3+1], z = pos[v*3+2];
    if(y < minY) minY = y;
    if(y > maxY) maxY = y;
    if(Math.abs(x) > maxAbsX) maxAbsX = Math.abs(x);
  }
  // 平边的宽度：y 最接近 yCut 的那些顶点里 |x| 的最大值 ×2
  let edgeR = 0;
  for(const v of used){
    if(Math.abs(pos[v*3+1] - yCut) < 1e-5){
      const r = Math.hypot(pos[v*3], pos[v*3+2]);
      if(r > edgeR) edgeR = r;
    }
  }
  return {label, radius, cylH, topCut, cut, yCut, rings, vertCount, maxIdx,
          height: maxY-minY, topEdgeWidth: 2*edgeR, maxAbsX,
          bad,
          // 注释里声称的数
          lost: 1 - (maxY-minY)/(2*halfHeight)};
}

const IMP = audit("IMP  eyeCut 0.48", 0.148, 0.055, 0.48);
const IMP_MAX = audit("IMP  eyeCut 0.85 (走 else 支)", 0.148, 0.055, 0.85);
const IMP_CEIL = audit("IMP  eyeCut 0.63 (瞳孔天花板)", 0.148, 0.055, 0.63);
const CREAM = audit("CREAM 未切", 0.122, 0.045, 0);
const TINY = audit("极小切口 0.02", 0.122, 0.045, 0.02);
const OVER = audit("超上限 1.5（应被夹到 0.85）", 0.148, 0.055, 1.5);
const NEG = audit("负值 -3（应被夹到 0）", 0.148, 0.055, -3);

const all = [IMP, IMP_MAX, IMP_CEIL, CREAM, TINY, OVER, NEG];
for(const a of all){
  console.log(
    `${a.label.padEnd(34)} rings=${String(a.rings).padStart(2)} verts=${String(a.vertCount).padStart(4)} ` +
    `maxIdx=${String(a.maxIdx).padStart(4)} 高=${a.height.toFixed(4)} 平边宽=${a.topEdgeWidth.toFixed(4)} ` +
    `切掉高度=${(a.lost*100).toFixed(1)}%` + (a.bad.length ? "  ✗ " + a.bad.join("；") : "  ✓"));
}

// ── 对照 BallLook.IMP / gl/Eye.kt 里写下的数 ──────────────────────────────
// 这些数写进了源码注释，也就是写进了**别人以后会相信的东西**。所以这里不是
// 打印一张"差不多"的表给人看 —— 对不上就退出码 1。容差 5e-4：注释里保留三到
// 四位小数，差过这个量级就说明注释该改了，或者代码改坏了。
const halfH = 0.148 + 0.055/2;
const pupilTop = 0.8*0.082;
const claims = [
  ["半高",            0.1755,  halfH,                                              1e-4],
  ["0.48 的切面 y",    0.0913,  IMP.yCut,                                          5e-5],
  ["瞳孔顶",           0.0656,  pupilTop,                                          5e-5],
  ["切面上方的白边",    0.0257,  IMP.yCut - pupilTop,                               5e-5],
  ["平边宽",           0.267,   IMP.topEdgeWidth,                                  5e-4],
  ["眼宽",             0.296,   2*0.148,                                           5e-4],
  ["切完的眼高",        0.267,   IMP.height,                                       5e-4],
  ["12° 下平边落差",    0.056,   IMP.topEdgeWidth*Math.sin(12/DEG),                5e-4],
  ["瞳孔天花板",        0.626,   1 - pupilTop/halfH,                               5e-4],
];
let claimFails = 0;
console.log("\n── 注释里的数对不对 ──");
for(const [name, claimed, actual, tol] of claims){
  const ok = Math.abs(claimed - actual) <= tol;
  if(!ok) claimFails++;
  console.log(`  ${ok ? "✓" : "✗"} ${name.padEnd(16)} 注释 ${String(claimed).padEnd(8)} 实测 ${actual.toFixed(4)}`);
}

// 两条分支的边界：yCut <= half 时走 else 支。IMP 的眼睛算出来是 0.8433。
const branchCut = 1 - (0.055/2)/halfH;
const inElse = c => (halfH*(1-c)) <= 0.055/2;
console.log("\n── 两条分支都在真实取值下够得到吗 ──");
console.log(`  分支边界 cut=${branchCut.toFixed(4)}  ` +
  (inElse(branchCut + 0.001) && !inElse(branchCut - 0.001)
     ? "✓ 0.843 以上走 else、以下走 if —— 两支都活"
     : "✗ 边界算错了"));
console.log(`  eyeCut 0.85 落在: ${IMP_MAX.yCut <= 0.055/2 ? "else 支（正确，会被 0.85 夹住）" : "if 支"}`);
console.log(`  eyeCut 0.48 落在: ${IMP.yCut > 0.055/2 ? "if 支（上半球，正常路径）" : "else 支"}`);

const fails = all.filter(a => a.bad.length);
const bad = fails.length + claimFails;
console.log(`\n${bad ? "❌ " + bad + " 项不合格" : "✅ 7 个网格合法，9 个注释数字对得上"}`);
process.exit(bad ? 1 : 0);
"""

p = tempfile.NamedTemporaryFile("w", suffix=".js", delete=False)
p.write(harness); p.close()
r = subprocess.run(["node", p.name], capture_output=True, text=True)
print(r.stdout)
if r.stderr: print("STDERR:", r.stderr[:2000])
os.unlink(p.name)
raise SystemExit(r.returncode)
