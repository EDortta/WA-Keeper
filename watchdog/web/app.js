"use strict";
const $=id=>document.getElementById(id);
const escape=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const MONTHS_PT=["JAN","FEV","MAR","ABR","MAI","JUN","JUL","AGO","SET","OUT","NOV","DEZ"];
function splitIsoDate(iso){const [year,month,day]=iso.split("-");return {day,month:MONTHS_PT[Number(month)-1]||month,year}}
let updateToken=null;
const cols=[["domain","Domínio",165],["purpose","Propósito",230],["owns","É dono de",215],["excludes","Não pode tocar",210],["inputs","Entradas",200],["outputs","Saídas / Eventos",220],["invariants","Invariantes",230],["status","Estado",130]].map(([id,name,width])=>({id,name,width,visible:true}));
let state={view:"map",tab:"description",sha:null,items:[],selected:null,commits:[],refs:[],head:null,domains:[],features:[]};
async function get(path){const r=await fetch(path);const data=await r.json();if(!r.ok)throw Error(data.error||r.status);return data}
async function load(){try{const [p,g]=await Promise.all([get("/api/project"),get("/api/commits")]);$("project").textContent=p.name;$("subtitle").textContent="Goals Kit Watchdog · "+p.branch;state.commits=g.commits;state.refs=g.refs;state.head=g.head;state.sha=g.head;updateToken=p.update_token;await revision(g.head)}catch(e){$("notice").textContent="Falha: "+e.message}}
async function revision(sha){state.sha=sha;try{[state.domains,state.features]=await Promise.all([get("/api/domains?sha="+sha),get("/api/features?sha="+sha)]);render()}catch(e){$("notice").textContent=e.message}}
state.compact=true;
state.graphFocus=false;
function navigate(view,selected){state.view=view;if(selected)state.selected=selected;state.tab="description";render()}
function groupedRefs(refs){
  // Collapse a local branch and its origin tracking ref only at the same SHA.
  // Other remotes remain separate unless their exact local counterpart exists.
  const names=[...new Set(refs)];
  const groups=new Map();
  for(const name of names){
    const local=name.startsWith("origin/")?name.slice(7):name;
    const key=names.includes(local)?local:name;
    if(!groups.has(key))groups.set(key,[]);
    groups.get(key).push(name);
  }
  return [...groups].map(([label,all])=>({label,all}));
}
function graph(){
  const nodes=state.commits.slice(0,250);
  const byId=new Map(nodes.map((n,i)=>[n.sha,i]));
  const parentsOf=new Map(nodes.map(n=>[n.sha,n.parents.filter(p=>byId.has(p))]));
  const childCount=new Map(nodes.map(n=>[n.sha,0]));
  for(const n of nodes)for(const p of parentsOf.get(n.sha))childCount.set(p,childCount.get(p)+1);
  const tips=new Set(state.refs.map(r=>r.sha));
  tips.add(state.head);
  const keep=new Set(nodes.filter(n=>n.parents.length!==1||childCount.get(n.sha)!==1||tips.has(n.sha)||n.sha===state.sha).map(n=>n.sha));
  if(nodes.length){keep.add(nodes[0].sha);keep.add(nodes[nodes.length-1].sha)}
  const compact=state.compact!==false;
  const visible=compact?nodes.filter(n=>keep.has(n.sha)):nodes;
  const visibleIds=new Set(visible.map(n=>n.sha));
  const main=new Set();let cur=state.head;
  while(byId.has(cur)&&!main.has(cur)){main.add(cur);cur=nodes[byId.get(cur)].parents[0]}
  const lane=new Map(),pending=new Map();
  function freeLane(){const used=new Set([0,...pending.values()]);let k=1;while(used.has(k))k++;return k}
  for(const n of nodes){
    let k=main.has(n.sha)?0:pending.get(n.sha);
    if(k===undefined)k=freeLane();
    pending.delete(n.sha);lane.set(n.sha,k);
    n.parents.forEach((p,i)=>{if(byId.has(p)&&!lane.has(p)&&!pending.has(p))pending.set(p,i===0?k:freeLane())});
  }
  const ordered=visible.slice().reverse();
  const gap=compact?85:64;
  const availableHeight=state.graphFocus?Math.max(170,$("graph").parentElement.clientHeight-28):0;
  const visibleLaneCount=new Set(visible.map(n=>lane.get(n.sha))).size;
  const laneGap=state.graphFocus
    ?Math.max(12,Math.min(95,Math.floor((availableHeight-84)/Math.max(1,visibleLaneCount-1))))
    :(compact?30:34);
  // Lane IDs may contain holes because inactive branches were released.
  // Remap only the lanes present in the displayed DAG to contiguous rows.
  const usedLanes=[...new Set(ordered.map(n=>lane.get(n.sha)))].sort((a,b)=>a-b);
  const rowForLane=new Map(usedLanes.map((id,row)=>[id,row]));
  const lastRow=Math.max(0,usedLanes.length-1);
  const width=Math.max(800,ordered.length*gap+70);
  const topPad=state.graphFocus?32:(compact?27:32);
  const bottomPad=state.graphFocus?52:(compact?37:42);
  const height=state.graphFocus?Math.max(availableHeight,topPad+lastRow*laneGap+bottomPad):topPad+lastRow*laneGap+bottomPad;
  const positions=new Map(ordered.map((n,i)=>[n.sha,{x:35+i*gap,y:topPad+rowForLane.get(lane.get(n.sha))*laneGap}]));
  const svg=$("graph"), scroll=svg.parentElement, previous=scroll.scrollLeft;
  svg.setAttribute("viewBox",`0 0 ${width} ${height}`);
  svg.style.width=width+"px";svg.style.height=height+"px";
  const visibleAncestor=sha=>{
    let p=sha,seen=new Set();
    while(byId.has(p)&&!visibleIds.has(p)&&!seen.has(p)){
      seen.add(p);p=nodes[byId.get(p)].parents[0];
    }
    return visibleIds.has(p)?p:null;
  };
  let edges="",points="",labels="",bands="",dates="";
  for(const n of ordered){
    const {x,y}=positions.get(n.sha);
    for(const parentSha of n.parents){
      const p=positions.get(visibleAncestor(parentSha));
      if(!p)continue;
      const mid=(p.x+x)/2;
      edges+='<path class="edge" d="M'+p.x+' '+p.y+' C'+mid+' '+p.y+' '+mid+' '+y+' '+x+' '+y+'"/>';
    }
    const refs=state.refs.filter(r=>r.sha===n.sha).map(r=>r.name);
    const title=escape(n.subject+" | "+n.committed+" | "+n.sha+" | "+(refs.join(", ")||"sem referência"));
    points+='<g class="node '+(state.sha===n.sha?'current':'')+'" tabindex="0" role="button" data-sha="'+n.sha+'" transform="translate('+x+','+y+')"><title>'+title+'</title><circle r="5"/>'+(compact?'':'<text x="7" y="12" transform="rotate(30 7 12)">'+escape(n.short)+'</text>')+'</g>';
    // Inline labels right-aligned at their branch tips, immediately above each lane.
    if(refs.length){
      // The rightmost tip of each slanted label is attached to its commit.
      // Exact rectangle width is measured after SVG insertion, not guessed.
      groupedRefs(refs).forEach(({label:ref,all},i)=>{
        const anchorX=x-7, anchorY=y-9-i*17;
        labels+='<g class="branch-inline graph-link" role="button" tabindex="0" data-sha="'+n.sha+'" '+
          'transform="translate('+anchorX+' '+anchorY+') rotate(-30)">'+
          '<title>'+escape(all.join(' · '))+'</title>'+
          '<rect x="-14" y="-13" width="14" height="17" rx="2"/>'+
          '<text x="-5" y="0" text-anchor="end">'+escape(ref)+'</text></g>';
      });
    }
  }
  // Every visible line receives a compact, explicit identity at its earliest
  // position. Without a named Git ref, show the abbreviated commit, not a guess.
  const startOfLane=new Map();
  for(const n of ordered)if(!startOfLane.has(lane.get(n.sha)))startOfLane.set(lane.get(n.sha),n);
  for(const [k,n] of startOfLane){
    const x=positions.get(n.sha).x,y=positions.get(n.sha).y;
    const direct=state.refs.filter(r=>r.sha===n.sha).map(r=>r.name);
    const name=direct[0]||(k===0?"linha principal":"ramo "+n.short);
    const w=Math.min(240,name.length*5.8+12);
    // A lane identity is not a Git branch name unless a ref actually exists.
    labels+='<g class="lane-identity graph-link" role="button" tabindex="0" data-sha="'+n.sha+'"><title>'+escape(direct.length?"Referência Git: "+name:"Identificação visual; branch sem ref neste commit")+'</title><text x="'+(x+8)+'" y="'+(y-11)+'">'+escape(name)+'</text></g>';
  }
  const bottom=height-9;
  let start=0,section=0;
  while(start<ordered.length){
    const day=ordered[start].committed.slice(0,10);
    let end=start+1;
    while(end<ordered.length&&ordered[end].committed.slice(0,10)===day)end++;
    const left=start===0?0:(positions.get(ordered[start-1].sha).x+positions.get(ordered[start].sha).x)/2;
    const right=end===ordered.length?width:(positions.get(ordered[end-1].sha).x+positions.get(ordered[end].sha).x)/2;
    const date=day.slice(8,10)+"/"+day.slice(5,7)+"/"+day.slice(0,4);
    if(section%2===1)bands+='<rect class="date-band" x="'+left+'" y="0" width="'+(right-left)+'" height="'+height+'"/>';
    if(start>0){
      bands+='<path class="date-divider" d="M'+left+' 0 V'+height+'"/>';
      const previous=ordered[start-1].committed.slice(0,10);
      // Both modes use the same three-line calendar block, at the baseline.
      const leftDate=splitIsoDate(previous),rightDate=splitIsoDate(day);
      const lineHeight=12,gap=compact?9:12;
      const dateY=height-35; // year baseline = height - 11
      function dateBlock(parts,x,anchor){
        return ['day','month','year'].map((part,i)=>
          '<text class="date-divider-block" x="'+x+'" y="'+(dateY+i*lineHeight)+'" text-anchor="'+anchor+'">'+escape(parts[part])+'</text>'
        ).join('');
      }
      dates+=dateBlock(leftDate,left-gap,"end")+dateBlock(rightDate,left+gap,"start");
    }
    // Repeat the day inside wide segments so the operator always has a date
    // even when horizontal scrolling hides both boundaries.
    const viewportWidth=Math.max(300,svg.parentElement.clientWidth);
    const spacing=Math.max(260,Math.floor(viewportWidth*0.75));
    const segmentWidth=right-left;
    if(!compact && segmentWidth>viewportWidth){
      for(let at=left+spacing/2;at<right;at+=spacing){
        dates+='<text class="date-label" x="'+at+'" y="'+(height-11)+'" text-anchor="middle">'+escape(date)+'</text>';
      }
    }else{
      dates+='<text class="date-label" x="'+((left+right)/2)+'" y="'+(height-11)+'" text-anchor="middle">'+escape(date)+'</text>';
    }
    start=end;section++;
  }
  svg.innerHTML='<g class="date-background">'+bands+'</g>'+edges+points+labels+'<g class="date-axis">'+dates+'</g>';
  // Measure actual text in the rendered SVG. Keep tight 5px padding.
  svg.querySelectorAll(".branch-inline").forEach(el=>{
    const text=el.querySelector("text"),rect=el.querySelector("rect");
    const w=text.getComputedTextLength();
    rect.setAttribute("x",String(-w-10));
    rect.setAttribute("width",String(w+10));
  });
  svg.querySelectorAll("[data-sha]").forEach(el=>{
    el.onclick=()=>revision(el.dataset.sha);
    el.onkeydown=e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();revision(el.dataset.sha)}};
  });
  if(!svg.dataset.initialized){scroll.scrollLeft=scroll.scrollWidth;svg.dataset.initialized="1"}else scroll.scrollLeft=previous;
  const toggle=$("graph-mode");
  if(toggle)toggle.textContent=compact?"Expandir commits":"Recolher commits";
  $("graph-height").textContent=state.graphFocus?"Restaurar altura":"Ampliar grafo";
  $("graph-height").setAttribute("aria-pressed",String(state.graphFocus));
}
function render(){graph();$("selected-rev").textContent=state.sha.slice(0,8)+" · "+(state.sha===state.head?"HEAD":"histórico");$("map").hidden=state.view!=="map";$("management").hidden=state.view==="map";for(const key of ["home","domains","features"])$(key).classList.toggle("active",state.view===(key==="home"?"map":key));$("view-name").textContent=state.view==="map"?"Mapa geral":state.view==="domains"?"Gestão de domínios":"Gestão de features";renderMap();if(state.view!=="map")renderManagement()}
function renderMap(){let visible=cols.filter(c=>c.visible);$("grid").innerHTML='<colgroup>'+visible.map(c=>'<col style="width:'+c.width+'px">').join("")+'</colgroup><thead><tr>'+visible.map(c=>'<th class="'+(c.id==="domain"?"frozen":"")+'"><div class="column-head">'+escape(c.name)+'<span style="display:flex">'+(c.id==="domain"?"":'<button data-hide="'+c.id+'" title="Ocultar">−</button>')+'<span class="resize" data-size="'+c.id+'"></span></span></div></th>').join("")+'</tr></thead><tbody>'+state.domains.map(d=>'<tr>'+visible.map(c=>'<td class="'+(c.id==="domain"?"frozen":"")+'">'+(c.id==="domain"?'<button class="domain-link" data-domain="'+escape(d.id)+'">'+escape(d.domain)+'</button>':escape(d[c.id]))+'</td>').join("")+'</tr>').join("")+'</tbody>';$("picker").innerHTML=cols.map(c=>'<label><input type="checkbox" data-toggle="'+c.id+'" '+(c.visible?"checked":"")+' '+(c.id==="domain"?"disabled":"")+'> '+escape(c.name)+'</label>').join("");document.querySelectorAll("[data-domain]").forEach(b=>b.onclick=()=>navigate("domains",b.dataset.domain));document.querySelectorAll("[data-hide]").forEach(b=>b.onclick=()=>{cols.find(c=>c.id===b.dataset.hide).visible=false;renderMap()});document.querySelectorAll("[data-toggle]").forEach(b=>b.onchange=()=>{cols.find(c=>c.id===b.dataset.toggle).visible=b.checked;renderMap()});document.querySelectorAll("[data-size]").forEach(el=>el.onpointerdown=e=>{e.preventDefault();const col=cols.find(c=>c.id===el.dataset.size),x=e.clientX,old=col.width;const move=ev=>{col.width=Math.max(80,Math.min(550,old+ev.clientX-x));const idx=cols.filter(c=>c.visible).indexOf(col);$("grid").querySelectorAll("col")[idx].style.width=col.width+"px"};const stop=()=>{window.removeEventListener("pointermove",move);window.removeEventListener("pointerup",stop)};window.addEventListener("pointermove",move);window.addEventListener("pointerup",stop)})}
function renderTree(n){return '<details open><summary>'+escape(n.title)+'</summary>'+(n.body.trim()?'<pre>'+escape(n.body.trim())+'</pre>':"")+(n.children||[]).map(renderTree).join("")+'</details>'}
function renderManagement(){const arr=state.view==="domains"?state.domains.map(d=>({...d,name:d.domain})):state.features;if(!arr.some(i=>i.id===state.selected))state.selected=arr[0]?.id||null;const d=arr.find(i=>i.id===state.selected);$("side-features").classList.toggle("active",state.view==="features");$("side-domains").classList.toggle("active",state.view==="domains");$("items").innerHTML=arr.map(i=>'<button class="item '+(i.id===state.selected?"active":"")+'" data-item="'+escape(i.id)+'"><strong>'+escape(i.name)+'</strong><br><small class="muted">'+escape(i.purpose||i.path||"")+'</small></button>').join("");document.querySelectorAll("[data-item]").forEach(b=>b.onclick=()=>{state.selected=b.dataset.item;state.tab="description";renderManagement()});if(!d){$("item-title").textContent="Nenhum item nesta revisão";$("item-description").textContent="";$("item-state").textContent="";$("detail").innerHTML="";return}$("item-title").textContent=d.name;$("item-description").textContent=d.purpose||"";$("item-state").textContent=d.status;document.querySelectorAll("[data-tab]").forEach(b=>b.classList.toggle("active",b.dataset.tab===state.tab));const detail=$("detail");if(state.tab==="description"&&d.path){detail.textContent="Carregando documentação…";get("/api/document?sha="+state.sha+"&path="+encodeURIComponent(d.path)).then(doc=>{if(state.tab==="description"&&state.selected===d.id)detail.innerHTML='<div class="tree">'+renderTree(doc.tree)+'</div>'}).catch(e=>detail.textContent=e.message)}else if(state.tab==="description")detail.innerHTML='<div class="tree"><details open><summary>Propósito</summary><p>'+escape(d.purpose)+'</p></details><details><summary>É dono de</summary><p>'+escape(d.owns)+'</p></details><details><summary>Não pode tocar</summary><p>'+escape(d.excludes)+'</p></details><details><summary>Invariantes</summary><p>'+escape(d.invariants)+'</p></details></div><p class="muted">Catálogo candidato; historicidade não estabelecida.</p>';else if(state.tab==="events")detail.innerHTML='<div class="tree"><b>Entradas</b><p>'+escape(d.inputs||"Não catalogadas")+'</p><b>Saídas</b><p>'+escape(d.outputs||"Não catalogadas")+'</p><p class="muted">Nomes propostos, não verificados no código.</p></div>';else detail.innerHTML='<p class="muted">'+(state.tab==="code"?"Indexação de código ainda não implementada.":"Nenhum teste executado nesta versão.")+'</p>'}
$("graph-mode").onclick=()=>{state.compact=!state.compact;graph()};
$("graph-height").onclick=()=>{
  state.graphFocus=!state.graphFocus;
  document.body.classList.toggle("graph-focus",state.graphFocus);
  graph();
};
window.addEventListener("resize",()=>{if(state.graphFocus)graph()});
document.addEventListener("keydown",e=>{
  if(e.key==="Escape"&&state.graphFocus){
    state.graphFocus=false;
    document.body.classList.remove("graph-focus");
    graph();
  }
});
async function updateAndRestart(){
  const button=$("update-restart");
  if(!updateToken||button.disabled)return;
  if(!confirm("Executar git pull --ff-only e reiniciar o Watchdog nesta mesma porta?"))return;
  button.disabled=true;button.textContent="Atualizando…";
  try{
    const response=await fetch("/api/update-restart",{
      method:"POST",headers:{"X-Watchdog-Token":updateToken}
    });
    const data=await response.json();
    if(!response.ok)throw Error(data.error||"Falha na atualização");
    button.textContent="Reiniciando…";
    $("notice").textContent=data.message||"Atualização concluída. Aguardando reinício…";
    // The old server closes after responding; new process takes the same port.
    for(let i=0;i<40;i++){
      await new Promise(resolve=>setTimeout(resolve,500));
      try{
        const ready=await fetch("/api/project",{cache:"no-store"});
        if(ready.ok){const info=await ready.json();if(info.head===data.head && info.update_token!==updateToken){location.reload();return}}
      }catch(_){}
    }
    throw Error("Reinício não confirmado. Verifique o terminal.");
  }catch(error){alert(error.message);$("notice").textContent=error.message}
  finally{button.disabled=false;button.textContent="Atualizar e reiniciar"}
}
$("update-restart").onclick=updateAndRestart;
$("home").onclick=()=>navigate("map");$("domains").onclick=$("side-domains").onclick=()=>navigate("domains");$("features").onclick=$("side-features").onclick=()=>navigate("features");document.querySelectorAll("[data-tab]").forEach(b=>b.onclick=()=>{state.tab=b.dataset.tab;renderManagement()});$("columns").onclick=()=>{$("picker").hidden=!$("picker").hidden};$("reset").onclick=()=>{cols.forEach(c=>c.visible=true);renderMap()};$("theme").onclick=()=>{document.body.classList.toggle("dark");localStorage.setItem("wd-theme",document.body.classList.contains("dark")?"dark":"light")};if(localStorage.getItem("wd-theme")==="dark")document.body.classList.add("dark");load();