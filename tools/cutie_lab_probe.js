/* 奶油球的耳朵 / 眉毛 —— 探针。
 *
 * ⚠️ **尚未在本机跑通过。** 写它的时候 headless Chrome 在这台机器上一个空白
 * 页都 dump 不出来（90 秒超时），所以下面这段代码没有被真实渲染验证过一遍。
 * 配套的 tools/verify_cutie_lab.py 里有同样的提示和判据阈值。
 *
 * 这个项目里"看不见图"是常态，所以结论必须能在没有眼睛的情况下被论证。
 * 这一支只做一件事：把同一个奶油球渲染四遍，每遍只关掉一样东西，
 * 于是"耳朵到底出了轮廓没有"、"眉毛到底有没有改变眼睛上方那些像素"
 * 就变成了像素差，而不是形容词。
 *
 *   全开       ears on  + brows on   —— 要装到机器上的那个
 *   只关耳朵   ears off + brows on
 *   只关眉毛   ears on  + brows off
 *   全关       ears off + brows off  —— 等于改动之前的奶油球，也就是基线
 *
 * 四个的**相位是同一个**（同样的 90 帧、同样的起始 t、autoFidget 关掉），
 * 否则两张图之间那点差会混进呼吸和摇晃里，量出来的东西没法对账。
 *
 * 输出写进 <pre id="zh-probe">，由 tools/verify_cutie_lab.py 抽出来。
 */
(function(){
  const OUT={facts:{},measure:{},checks:[],rows:[]};
  const P=document.createElement('pre');
  P.id='zh-probe'; P.style.display='none';
  document.body.appendChild(P);
  function fail(e){ OUT.fatal=String(e&&e.stack||e); P.textContent=JSON.stringify(OUT); }

  try{
    const LAB=window.LAB;
    if(!LAB||!LAB.Ball||!LAB.edited) throw new Error('页面上没有 window.LAB');

    const SZ=420;
    const cv=document.createElement('canvas');
    cv.style.cssText='position:fixed;left:-9999px;top:0;width:'+SZ+'px;height:'+SZ+'px';
    document.body.appendChild(cv);

    const base=JSON.parse(JSON.stringify(LAB.edited.cream));

    /* ---- 一次快照 ---- */
    const IM={};
    function shoot(key, over){
      const params=Object.assign({}, base, over||{});
      const b=new LAB.Ball(cv, params);
      b.autoFidget=false;
      // 静止脸。耳朵和眉毛在 CALM 下最该被看清 —— 那是球一天里绝大多数
      // 时间的样子，也是"机灵"这个诉求真正要落的地方。
      b.mood='CALM';
      b.resize();
      let t=3.0;
      for(let i=0;i<90;i++){ t+=1/60; b.frame(1/60,t); }
      const w=cv.width, h=cv.height;
      const px=new Uint8Array(w*h*4);
      b.gl.readPixels(0,0,w,h,b.gl.RGBA,b.gl.UNSIGNED_BYTE,px);
      IM[key]={px,w,h,params:params,eyeTopY:null};
      return IM[key];
    }

    const both =shoot('both',   {});
    const earOff=shoot('earOff',{earH:0});
    const browOff=shoot('browOff',{browW:0});
    const none =shoot('none',   {earH:0, browW:0});

    /* ---- 像素工具。注意 readPixels 的第 0 行是**屏幕底部**，
     *      所以凡是给人看的东西（字符画、行号、质心高低）都要翻过来。 ---- */
    const w=both.w, h=both.h;

    function opaque(im,i){ return im.px[i*4+3]>128; }

    /** 不透明像素的最大半径（以图心为圆心）。球是居中的，透视下也一样。 */
    function maxRadius(im){
      const cx=w/2, cy=h/2;
      let rmax=0, n=0;
      for(let y=0;y<h;y++)for(let x=0;x<w;x++){
        if(!opaque(im,y*w+x)) continue;
        n++;
        const r=Math.hypot(x+0.5-cx, y+0.5-cy);
        if(r>rmax) rmax=r;
      }
      return {rmax:rmax,n:n};
    }

    /** 两张图的差异像素：数量、质心（**从上往下的行号**）、逐行分布。 */
    function diff(a,b){
      const ROWS=28;
      const rows=new Array(ROWS).fill(0);
      let n=0,sx=0,sy=0, maxd=0;
      const map=new Uint8Array(w*h);
      for(let y=0;y<h;y++)for(let x=0;x<w;x++){
        const i=(y*w+x)*4;
        const d=Math.abs(a.px[i]-b.px[i])+Math.abs(a.px[i+1]-b.px[i+1])
               +Math.abs(a.px[i+2]-b.px[i+2])+Math.abs(a.px[i+3]-b.px[i+3]);
        if(d>24){
          map[y*w+x]=1; n++; sx+=x; sy+=y;
          if(d>maxd) maxd=d;
          rows[Math.floor((h-1-y)/h*ROWS)]++;
        }
      }
      return {n:n, maxd:maxd,
              cx: n?sx/n:null,
              cyFromTop: n?(h-1-sy/n):null,
              rows:rows, map:map};
    }

    function art(im,cols,rows){
      const ramp=' .:-=+*#%@';
      const lines=[];
      for(let r=0;r<rows;r++){
        let s='';
        const y=Math.min(h-1,Math.floor((rows-1-r+0.5)*h/rows));
        for(let c=0;c<cols;c++){
          const x=Math.min(w-1,Math.floor((c+0.5)*w/cols));
          if(!opaque(im,y*w+x)){ s+=' '; continue; }
          const i=(y*w+x)*4;
          const L=(0.2126*im.px[i]+0.7152*im.px[i+1]+0.0722*im.px[i+2])/255;
          s+=ramp[1+Math.min(7,Math.floor(L*7.999))];
        }
        lines.push(s);
      }
      return lines;
    }

    function alphaArt(im,cols,rows){
      const lines=[];
      for(let r=0;r<rows;r++){
        let s='';
        const y=Math.min(h-1,Math.floor((rows-1-r+0.5)*h/rows));
        for(let c=0;c<cols;c++){
          const x=Math.min(w-1,Math.floor((c+0.5)*w/cols));
          s += opaque(im,y*w+x) ? '#' : ' ';
        }
        lines.push(s);
      }
      return lines;
    }

    function mapArt(map,cols,rows){
      const lines=[];
      for(let r=0;r<rows;r++){
        let s='';
        const y=Math.min(h-1,Math.floor((rows-1-r+0.5)*h/rows));
        for(let c=0;c<cols;c++){
          const x=Math.min(w-1,Math.floor((c+0.5)*w/cols));
          s += map[y*w+x] ? '#' : (opaque(both,y*w+x)?'.':' ');
        }
        lines.push(s);
      }
      return lines;
    }

    /* ---- 量 ---- */
    const R={both:maxRadius(both), earOff:maxRadius(earOff),
             browOff:maxRadius(browOff), none:maxRadius(none)};
    const D_ears=diff(both,browOff);      // 只差在耳朵
    const D_brows=diff(both,earOff);      // 只差在眉毛
    const D_all=diff(both,none);          // 耳朵 + 眉毛

    const R_body=R.none.rmax;             // 基线（无耳无眉）的最大半径 = 球 + 描边
    const outPx=R.both.rmax-R_body;       // 耳朵伸出去多少像素

    // 眼睛中心在图上的行号（从上往下）。拿参数算，不猜。
    // 面平面上的 y 是向上为正，屏幕上也是；图心 = 0。
    // 位置到像素的换算：resize() 里 proj 是 42° 竖直 FOV、near 0.1、far 100，
    // 球心在原点，所以屏幕高度对应 2*tan(21°)*|z_cam|。球在 z=0，
    // 相机在 view 里后退了一段距离 —— 直接用基线球半径标定更省事：
    //   世界半径 R_body_world（含描边）≈ 0.845 + 0.03   对应 R_body 像素
    // 于是 1 世界单位 = R_body / R_body_world 像素。
    const OUTLINE_W=0.03;                       // 与页面里的描边厚度同量级
    const pxPerUnit=R_body/(both.params.bodyScale+OUTLINE_W);
    const eyeYpx=(h/2)-both.params.eyeY*pxPerUnit;     // 行号（从上往下）
    const browYpx=(h/2)-(both.params.eyeY+both.params.browY)*pxPerUnit;

    OUT.facts={
      size:[w,h],
      bodyScale:both.params.bodyScale,
      eyeX:both.params.eyeX, eyeY:both.params.eyeY, eyeRadius:both.params.eyeRadius,
      browW:both.params.browW, browH:both.params.browH, browY:both.params.browY,
      browTilt:both.params.browTilt,
      earH:both.params.earH, earW:both.params.earW, earX:both.params.earX,
      earY:both.params.earY, earTilt:both.params.earTilt, earBend:both.params.earBend,
      earShade:both.params.earShade,
      pxPerUnit:pxPerUnit,
      bodyRadiusPx:R_body,
      eyeRowFromTop:eyeYpx,
      browRowFromTop:browYpx,
    };

    OUT.measure={
      radiusPx:{both:R.both.rmax, earOff:R.earOff.rmax,
                browOff:R.browOff.rmax, none:R.none.rmax},
      earOutPx:outPx,
      // 出轮廓的宽度 ÷ 球直径。这是"耳朵到底算不算长在轮廓外面"的唯一硬指标。
      earOutFracOfDiameter: outPx/(2*R_body),
      opaquePx:{both:R.both.n, earOff:R.earOff.n, browOff:R.browOff.n, none:R.none.n},
      // 有新像素才说明耳朵真的伸到了轮廓外；只是把轮廓内的一块换了个颜色
      // 会让这个数是 0，那就不叫"立耳"。
      earNewPx:D_ears.n,
      browDiffPx:D_brows.n,
      browDiffMaxDelta:D_brows.maxd,
      browDiffCentroidFromTop:D_brows.cyFromTop,
      browDiffRows:D_brows.rows,
      allDiffPx:D_all.n,
      allDiffRows:D_all.rows,
      // 球体自身有没有被意外动过：只在"轮廓内、且不在眉毛带"的地方找差异。
      // 眉毛带 = 眼睛中心往上到 browY 再往上留一点余量。
      unexpectedPx:(function(){
        let n=0;
        const bandTop=(h/2)-(both.params.eyeY+both.params.browY+0.12)*pxPerUnit;
        const rInside=R_body-3;
        const cx=w/2, cy=h/2;
        for(let y=0;y<h;y++)for(let x=0;x<w;x++){
          if(!D_all.map[y*w+x]) continue;
          const r=Math.hypot(x+0.5-cx, y+0.5-cy);
          if(r<=rInside && y>bandTop) n++;      // 轮廓内的、眉毛带以下的差异
        }
        return n;
      })(),
    };

    OUT.rows=[
      {label:'奶油球 · 全开（要装机的样子）', ascii:art(both,68,34), alpha:alphaArt(both,68,34)},
      {label:'奶油球 · 基线（无耳无眉）',     ascii:art(none,68,34), alpha:alphaArt(none,68,34)},
      {label:'差异图 · 耳朵（全开 vs 只关耳）', ascii:mapArt(D_ears.map,68,34), alpha:alphaArt(none,68,34)},
      {label:'差异图 · 眉毛（全开 vs 只关眉）', ascii:mapArt(D_brows.map,68,34), alpha:alphaArt(none,68,34)},
    ];

    OUT.version='v1';
  }catch(e){ fail(e); }

  document.getElementById('zh-probe').textContent=JSON.stringify(OUT);
})();
