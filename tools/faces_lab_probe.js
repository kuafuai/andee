/* =========================================================================
   球球性格实验室 —— 像素级验收探针
   =========================================================================
   把它注入到 lab 页面的 `</body>` 之前，用 headless Chrome 跑一遍，从页面上
   **量**出结果，而不是靠看。结果写进一个探针结果块（id = zh-probe），由
   `tools/verify_faces_lab.py` 抽出来。那个取结果的脚本要留意：探针的源码本身
   也会出现在 --dump-dom 的结果里，所以它按"能解析成 JSON 的那一段"去取，
   而不是取第一个匹配。

   设计原则跟这个项目里别的地方一样：**每一句设计主张，都要有一个数。**
   这里验收的是这几句：

     1. 页面跑起来了，没有异常。（硬失败）
     2. "可爱住在骨头上" —— 五套新预设的眼宽 / 眼睛高低 / 眼距 / 面平面 /
        腮红大小，跟已经装机的奶油球**逐位相同**。
     3. 眉真的画出来了 —— brow 从 0 拉到 1，眼睛上方那片区域必须有像素变化。
     4. 牙真的画出来了 —— fang 0→1，嘴附近必须有像素变化。
     5. 张嘴是**一块面**不是一根线 —— open 嘴的着色像素数必须远多于 arc 嘴。
     6. "很邪恶但也很可爱" —— 眉/牙/怒符这些暗色件不能把整颗球吃掉：
        球体自身的像素量不能被改变超过一个很小的比例。
     7. 腹黑的**不对称**是真的 —— 左右镜像之后像素必须对不上。
     8. 蒸汽在球顶**外面** —— 球顶以上必须出现非透明像素。
   ========================================================================= */
(function(){
  const R={version:(window.LAB&&window.LAB.version)||'?', checks:[], facts:{}, fatal:null};
  const F=document.createElement('pre');
  F.id='zh-probe';
  F.style.display='none';
  document.body.appendChild(F);

  function add(name,ok,detail,value){
    R.checks.push({name:name, ok:!!ok, detail:detail, value:(value===undefined?null:value)});
  }
  function done(){
    F.textContent=JSON.stringify(R);
  }

  try{
    if(!window.LAB) throw new Error('window.LAB 没出现：build() 大概抛异常了。err 框内容='
      + JSON.stringify((document.getElementById('err')||{}).textContent||''));
    const LAB=window.LAB, PRESETS=LAB.PRESETS;

    /* 把画布放大一点，不然 168px 上数不出东西。
       改的是 CSS 而不是 canvas.width —— Ball 是读 clientWidth 的。 */
    const css=document.createElement('style');
    css.textContent=
      '.grid{grid-template-columns:repeat(3,1fr)!important}'+
      '.cell .stage{aspect-ratio:1!important}'+
      '.hstage canvas{width:520px!important}';
    document.head.appendChild(css);

    R.facts.presets=PRESETS.map(p=>p.key);

    /* ---------- 取样工具 ---------- */
    function stage(key){
      const s=LAB.stages.find(x=>x.key===key);
      if(!s) throw new Error('没有这个预设: '+key);
      return s;
    }
    /** 把一套参数装上去，settle 若干帧（tick 里有平滑，一帧不够），
     *  然后读回像素。读必须紧跟在 frame() 之后、在同一个任务里 ——
     *  上下文没开 preserveDrawingBuffer，一旦让出控制权缓冲就没了。 */
    function grab(key, params, mood, frames){
      const s=stage(key), b=s.ball;
      b.resize();
      if(params){ b.setParams(params); }
      b.mood=mood||(PRESETS.find(p=>p.key===key).demoMood||'CALM');
      b.autoFidget=false;
      const gl=b.gl, w=b.cv.width, h=b.cv.height;
      let tt=3.0;
      for(let i=0;i<(frames||48);i++){ tt+=1/60; b.frame(1/60,tt); }
      const px=new Uint8Array(w*h*4);
      gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,px);
      return {px:px,w:w,h:h,ball:b};
    }
    function luma(p,i){ return 0.2126*p[i]+0.7152*p[i+1]+0.0722*p[i+2]; }
    function cloneParams(o){ return JSON.parse(JSON.stringify(o)); }
    /** 两层之间的差异统计。返回改变的像素数、最大差、以及改变区域的质心。 */
    function diff(a,bq,minDelta){
      const n=a.px.length; let cnt=0, mx=0, sx=0, sy=0;
      for(let i=0;i<n;i+=4){
        const dv=Math.abs(luma(a.px,i)-luma(bq.px,i));
        if(dv>=(minDelta||14)){
          cnt++; if(dv>mx) mx=dv;
          const q=i>>2; sx+=(q%a.w); sy+=Math.floor(q/a.w);
        }
      }
      return {count:cnt, max:mx,
              cx:cnt?sx/cnt:0, cy:cnt?sy/cnt:0,
              frac:cnt/(a.w*a.h)};
    }
    function opaque(im){
      let c=0, sx=0, sy=0;
      for(let i=3;i<im.px.length;i+=4) if(im.px[i]>28){ c++; const q=(i-3)>>2; sx+=q%im.w; sy+=Math.floor(q/im.w); }
      return {count:c, cx:c?sx/c:c/2, cy:c?sy/c:0, frac:c/(im.w*im.h)};
    }

    /* ---------- 1. 每套预设都能画出东西 ---------- */
    for(const p of PRESETS){
      const im=grab(p.key, LAB.edited[p.key], p.demoMood);
      const o=opaque(im);
      add('① '+p.label+' 画出了一颗球',
          o.frac>0.06 && o.frac<0.85,
          '不透明像素占画布 '+ (o.frac*100).toFixed(1) +'%',
          {frac:+o.frac.toFixed(4), cx:+(o.cx/im.w).toFixed(3), cy:+(o.cy/im.h).toFixed(3)});
    }

    /* ---------- 2. 骨架没动：可爱度是共享的 ---------- */
    const base=(LAB.edited.cream||{});
    const SKEL=['eyeR','eyeY','eyeX','faceZ','blushSize','blushX','blushY','eyeH'];
    const drift=[];
    for(const p of PRESETS){
      if(p.key==='cream') continue;
      const q=LAB.edited[p.key];
      for(const k of SKEL){
        if(base[k]===undefined) continue;
        if(Math.abs((q[k]||0)-(base[k]||0))>1e-9){
          drift.push(p.key+'.'+k+' '+q[k]+'≠'+base[k]);
        }
      }
    }
    const SKEL_KEYS=SKEL.map(k=>k+'='+base[k]).join(' ');
    add('② 五套新骨架 === 奶油球',
        drift.length===0,
        drift.length? ('漂了: '+drift.join(', ')) : ('逐位相同：'+SKEL_KEYS),
        {checked:SKEL.length*(PRESETS.length-1)});

    /* ---------- 3. 眉：拉起来必须真的多出像素 ---------- */
    {
      const im0=grab('imp', Object.assign(cloneParams(LAB.edited.imp),{brow:0}),'CALM');
      const im1=grab('imp', Object.assign(cloneParams(LAB.edited.imp),{brow:1}),'CALM');
      const d=diff(im0,im1,14);
      // 眉在眼睛**上方**：变化像素的质心必须在画面中线以上，而且大致居中
      const cyN=d.cy/im0.h, cxN=d.cx/im0.w;
      const up = cyN < 0.47;
      const mid= Math.abs(cxN-0.5) < 0.16;
      add('③ 眉画出来了，而且在眼睛上方',
          d.count>150 && up && mid,
          'brow 0→1 改了 '+d.count+' px（'+d.max.toFixed(0)+'/255），质心 y='+cyN.toFixed(3)+' x='+cxN.toFixed(3)+
            '（要在中线以上、水平居中）',
          {count:d.count, cy:+cyN.toFixed(3), cx:+cxN.toFixed(3)});
    }

    /* ---------- 4. 牙：一颗和两颗都要有 ---------- */
    for(const [key,lbl] of [['imp','小恶魔（一颗虎牙）'],['vamp','吸血鬼（两颗獠牙）'],['neko','猫妖（ω 里的两颗）']]){
      const q=cloneParams(LAB.edited[key]);
      const im0=grab(key, Object.assign(cloneParams(q),{fang:0}), q.demoMood);
      const im1=grab(key, Object.assign(cloneParams(q),{fang:q.fang||2}), q.demoMood);
      const d=diff(im0,im1,14);
      const cyN=d.cy/im0.h;
      add('④ '+lbl+' 的牙画出来了',
          d.count>60 && cyN > 0.5,
          'fang 0→'+(q.fang||2)+' 改了 '+d.count+' px，质心 y='+cyN.toFixed(3)+'（要在中线以下）',
          {count:d.count, cy:+cyN.toFixed(3)});
    }

    /* ---------- 5. 张嘴是一块面，不是一根线 ---------- */
    {
      // 用同一套"很厚"的嘴比：arc 嘴把管径加到 0.09，看谁面积大
      const arc=Object.assign(cloneParams(LAB.edited.grumpy),
        {mouthStyle:'arc', mouthTube:0.090, mouthMode:'mood'});
      const opn=Object.assign(cloneParams(LAB.edited.grumpy),
        {mouthStyle:'open', mouthMode:'mood', teeth:0, teethRows:1});
      const ia=grab('grumpy', arc,'CALM');
      const io=grab('grumpy', opn,'CALM');
      // 嘴的着色像素：用"深色且在下半脸"的像素数近似
      function mouthPixels(im){
        let c=0;
        for(let y=Math.floor(im.h*0.5); y<im.h; y++){
          for(let x=0;x<im.w;x++){
            const i=(y*im.w+x)*4;
            if(im.px[i+3]>128 && luma(im.px,i)<70) c++;
          }
        }
        return c;
      }
      const ma=mouthPixels(ia), mo=mouthPixels(io);
      add('⑤ 张嘴是一块面（不是一根粗线）',
          mo > ma*2.0 && mo>400,
          '同样"很厚"的设定下：arc 嘴 '+ma+' px，open 嘴 '+mo+' px，比 '+(mo/Math.max(1,ma)).toFixed(1)+'×',
          {arc:ma, open:mo, ratio:+(mo/Math.max(1,ma)).toFixed(2)});
    }

    /* ---------- 6. 邪恶件没有把可爱吃掉 ---------- */
    {
      const q=cloneParams(LAB.edited.imp);
      const stripped=Object.assign(cloneParams(q),{brow:0,fang:0,ticks:0,ears:false});
      const i0=grab('imp',stripped,'CALM');
      const i1=grab('imp',q,'CALM');
      const o0=opaque(i0), o1=opaque(i1);
      const d=Math.abs(o1.frac-o0.frac)/Math.max(1e-6,o0.frac);
      add('⑥ 加上眉/牙/勾之后，球体自身没有被改掉',
          d<0.06,
          '不透明像素占比 '+(o0.frac*100).toFixed(2)+'% → '+(o1.frac*100).toFixed(2)+'%（变了 '+(d*100).toFixed(2)+'%）',
          {before:+o0.frac.toFixed(4), after:+o1.frac.toFixed(4)});
    }

    /* ---------- 7. 腹黑的不对称是真的 ---------- */
    {
      const q=cloneParams(LAB.edited.smug);
      const sym=Object.assign(cloneParams(q),{browSkew:0});
      const is=grab('smug',q,'CURIOUS');
      const iy=grab('smug',sym,'CURIOUS');
      function mirrorDiff(im){
        let c=0;
        for(let y=0;y<im.h;y++){
          for(let x=0;x<Math.floor(im.w/2);x++){
            const a=(y*im.w+x)*4, b=(y*im.w+(im.w-1-x))*4;
            const da=im.px[a+3], db=im.px[b+3];
            if(da>128 && db>128 && Math.abs(luma(im.px,a)-luma(im.px,b))>18) c++;
          }
        }
        return c;
      }
      const ms=mirrorDiff(is), my=mirrorDiff(iy);
      add('⑦ 腹黑：左右镜像之后确实对不上',
          ms > my*2.0 && ms>120,
          'skew 0.62 时镜像差异 '+ms+' px，skew 0 时 '+my+' px —— 不对称是这对眉做出来的',
          {skewed:ms, symmetric:my});
    }

    /* ---------- 8. 蒸汽在球顶外面 ---------- */
    {
      const q=cloneParams(LAB.edited.grumpy);
      const i0=grab('grumpy', Object.assign(cloneParams(q),{steam:0,vein:0}), q.demoMood);
      const i1=grab('grumpy', Object.assign(cloneParams(q),{steam:1,vein:1}), q.demoMood);
      // 只看画面上方 22% 那条带：球顶在那里已经结束，那里出现的不透明像素只能是蒸汽
      function topBand(im){
        let c=0;
        for(let y=0;y<Math.floor(im.h*0.22);y++)
          for(let x=0;x<im.w;x++) if(im.px[(y*im.w+x)*4+3]>60) c++;
        return c;
      }
      const t0=topBand(i0), t1=topBand(i1);
      add('⑧ 蒸汽出现在球顶以上的空白里',
          t1 > 60 && t1 > t0*3,
          '画面顶部 22% 那一条带：无蒸汽 '+t0+' px，有蒸汽 '+t1+' px',
          {off:t0, on:t1});
    }

    /* ---------- 附：怒符单独一格 ---------- */
    {
      const q=cloneParams(LAB.edited.grumpy);
      const i0=grab('grumpy', Object.assign(cloneParams(q),{vein:0,steam:0}), q.demoMood);
      const i1=grab('grumpy', Object.assign(cloneParams(q),{vein:1,steam:0}), q.demoMood);
      const d=diff(i0,i1,14);
      add('⑨ 怒符画出来了',
          d.count>80,
          'vein 0→1 改了 '+d.count+' px，质心 y='+(d.cy/i0.h).toFixed(3),
          {count:d.count});
    }

    // 复原现场，免得后面的截图看到被改过的参数
    for(const p of PRESETS) stage(p.key).ball.setParams(LAB.edited[p.key]);

  }catch(e){
    R.fatal=String(e&&e.stack||e);
  }
  done();
})();
