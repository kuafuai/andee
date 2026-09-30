/* ASCII 验脸器 —— 在没有视觉的情况下"看"这张脸。
 *
 * 这个项目的一贯做法：把画布上的像素解码成字符画，用形状说话。
 * 这里额外做一件事：把渲染器的每个绘制函数包一层计数器，
 * 这样"这块件到底画了没有"就不用猜了。
 *
 * 输出写进 <pre id="zh-ascii">，由 tools/dump_faces_ascii.py 抽出来。
 */
(function(){
  const OUT={rows:[], notes:[], counts:{}};
  const P=document.createElement('pre'); P.id='zh-ascii'; P.style.display='none';
  document.body.appendChild(P);

  function fail(e){
    OUT.fatal=String(e&&e.stack||e);
    P.textContent=JSON.stringify(OUT);
  }

  try{
    const LAB=window.LAB;
    const css=document.createElement('style');
    css.textContent='.grid{grid-template-columns:repeat(3,1fr)!important}';
    document.head.appendChild(css);

    /* ---- 绘制计数器：包住 Ball.prototype 上的那批 draw* ---- */
    const B=LAB.Ball.prototype;
    const METHODS=['drawBrows','drawMouth','drawVein','drawSteam','drawEars','drawWedge','drawBody'];
    for(const m of METHODS){
      if(typeof B[m]!=='function'){ OUT.notes.push('缺少方法 Ball.'+m); continue; }
      OUT.counts[m]=0;
      const orig=B[m];
      B[m]=function(){ OUT.counts[m]++; return orig.apply(this,arguments); };
    }

    function stageOf(k){ return LAB.stages.find(s=>s.key===k); }

    /** 把一张画布变成字符画。alpha 低的当空白。
     *
     *  **行序**：gl.readPixels 返回的是**从下往上**的行，所以第 0 行是屏幕底部。
     *  这里统一翻过来，让字符画的第 0 行就是屏幕顶部 —— 否则每看一次就要在脑子里
     *  倒一次，而"眉在上、牙在下"这类判断全靠方向。
     */
    function art(im,cols,rows){
      const {px,w,h}=im;
      const ramp=' .:-=+*#%@';
      const lines=[];
      for(let r=0;r<rows;r++){
        let s='';
        const y=Math.min(h-1,Math.floor((rows-1-r+0.5)*h/rows));
        for(let c=0;c<cols;c++){
          const x=Math.min(w-1,Math.floor((c+0.5)*w/cols));
          const i=(y*w+x)*4;
          const a=px[i+3]/255;
          if(a<0.06){ s+=' '; continue; }
          const L=(0.2126*px[i]+0.7152*px[i+1]+0.0722*px[i+2])/255;
          s+=ramp[1+Math.min(7,Math.floor(L*7.999))];
        }
        lines.push(s);
      }
      return lines;
    }

    /** 只画 alpha，用来看"哪些像素存在"（忽略亮度）。 */
    function alphaArt(im,cols,rows){
      const {px,w,h}=im;
      const lines=[];
      for(let r=0;r<rows;r++){
        let s='';
        const y=Math.min(h-1,Math.floor((rows-1-r+0.5)*h/rows));
        for(let c=0;c<cols;c++){
          const x=Math.min(w-1,Math.floor((c+0.5)*w/cols));
          const a=px[(y*w+x)*4+3]/255;
          s += a<0.06?' ': a<0.5?'.':'#';
        }
        lines.push(s);
      }
      return lines;
    }

    function render(key,over,mood,frames){
      const s=stageOf(key), b=s.ball;
      const params=Object.assign({}, JSON.parse(JSON.stringify(LAB.edited[key])), over||{});
      b.resize();
      b.setParams(params);
      b.mood=mood||(LAB.PRESETS.find(p=>p.key===key).demoMood||'CALM');
      b.autoFidget=false;
      let t=4.0;
      for(let i=0;i<(frames||60);i++){ t+=1/60; b.frame(1/60,t); }
      const w=b.cv.width,h=b.cv.height;
      const px=new Uint8Array(w*h*4);
      b.gl.readPixels(0,0,w,h,b.gl.RGBA,b.gl.UNSIGNED_BYTE,px);
      b.setParams(LAB.edited[key]);
      return {px,w,h};
    }

    const WANT=[
      ['imp',    {},                       'CALM',   '小恶魔 · 原样'],
      ['imp',    {brow:0,fang:0,ticks:0},  'CALM',   '小恶魔 · 拆掉眉和牙'],
      ['imp',    {brow:0},                 'CALM',   '小恶魔 · 只拆眉'],
      ['imp',    {browTilt:-0.30},         'CALM',   '小恶魔 · 眉反过来（委屈）'],
      ['grumpy', {},                       'TENSE',  '暴躁球 · 原样'],
      ['grumpy', {steam:0,vein:0},         'TENSE',  '暴躁球 · 拆掉蒸汽和怒符'],
      ['grumpy', {teeth:0,gawp:0.20},      'TENSE',  '暴躁球 · 牙少一点、嘴小一圈'],
      ['vamp',   {},                       'CALM',   '吸血鬼球 · 原样'],
      ['vamp',   {fang:0},                 'CALM',   '吸血鬼球 · 拆掉獠牙'],
      ['neko',   {},                       'HAPPY',  '猫妖 · 原样'],
      ['cream',  {},                       'CALM',   '奶油球（基线）'],
    ];

    for(const [key,over,mood,label] of WANT){
      const before=Object.assign({},OUT.counts);
      const im=render(key,over,mood);
      const calls={};
      for(const m of METHODS) calls[m]=OUT.counts[m]-before[m];
      OUT.notes.push({label:label, key:key, mood:mood, size:[im.w,im.h],
                      over:Object.keys(over), calls:calls});
      OUT.rows.push({label:label, ascii:art(im,68,30), alpha:alphaArt(im,68,30)});
    }

    for(const m of METHODS) delete OUT.counts[m];
    P.textContent=JSON.stringify(OUT);
  }catch(e){ fail(e); }
})();
