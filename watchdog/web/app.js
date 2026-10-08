"use strict";
const $=id=>document.getElementById(id);
const escape=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const cols=[["domain","Domínio",165],["purpose","Propósito",230],["owns","É dono de",215],["excludes","Não pode tocar",210],["inputs","Entradas",200],["outputs","Saídas / Eventos",220],["invariants","Invariantes",230],["status","Estado",130]].map(([id,name,width])=>({id,name,width,visible:true}));
let state={view:"map",tab:"description",sha:null,items:[],selected:null,commits:[],refs:[],head:null,domains:[],features:[]};
async function get(path){const r=await fetch(path);const data=await r.json();if(!r.ok)throw Error(data.error||r.status);return data}
async function load(){try{const [p,g]=await Promise.all([get("/api/project"),get("/api/commits")]);$("project").textContent=p.name;$("subtitle").textContent="Goals Kit Watchdog · "+p.branch;state.commits=g.commits;state.refs=g.refs;state.head=g.head;state.sha=g.head;await revision(g.head)}catch(e){$("notice").textContent="Falha: "+e.message}}
async function revision(sha){state.sha=sha;try{[state.domains,state.features]=await Promise.all([get("/api/domains?sha="+sha),get("/api/features?sha="+sha)]);render()}catch(e){$("notice").textContent=e.message}}
function navigate(view,selected){state.view=view;if(selected)state.selected=selected;state.tab="description";render()}
function graph(){
  // Walk the DAG newest -> oldest: a commit's FIRST parent inherits its lane.
  // Additional merge parents get side lanes. The HEAD first-parent line stays on lane 0.
  const nodes=state.commits.slice(0,250);
  const byId=new Map(nodes.map((c,i)=>[c.sha,i]));
  const firstParent=new Set();
  let cursor=state.head;
  while(byId.has(cursor)&&!firstParent.has(cursor)){
    firstParent.add(cursor);
    cursor=nodes[byId.get(cursor)].parents[0];
  }
  const assigned=new Map(), pending=new Map();
  const freeLane=()=>{const used=new Set([0,...pending.values()]);let lane=1;while(used.has(lane))lane++;return lane};
  for(const n of nodes){
    let lane=firstParent.has(n.sha)?0:pending.get(n.sha);
    if(lane===undefined)lane=freeLane();
    pending.delete(n.sha);
    assigned.set(n.sha,lane);
    for(let i=0;i<n.parents.length;i++){
      const parent=n.parents[i];
      if(!byId.has(parent)||assigned.has(parent))continue;
      if(!pending.has(parent))pending.set(parent,i===0?lane:freeLane());
    }
  }
  // Oldest to newest, but still topological. Actual commit dates are shown on the axis.
  const ordered=nodes.slice().reverse();
  const px=62, step=66, maxLane=Math.max(0,...assigned.values());
  const width=Math.max(800,ordered.length*step+60),height=Math.max(135,55+maxLane*24+55);
  const positions=new Map(ordered.map((n,i)=>[n.sha,{x:35+i*step,y:25+assigned.get(n.sha)*24}]));
  const graphEl=$("graph");
  const former=graphEl.parentElement.scrollLeft;
  graphEl.setAttribute("viewBox",`0 0 ${width} ${height}`);
  graphEl.style.width=width+"px";
  graphEl.style.height=height+"px";
  let edges="",circles="";
  for(const n of ordered){
    const {x,y}=positions.get(n.sha);
    for(let i=0;i<n.parents.length;i++){
      const parent=positions.get(n.parents[i]);
      if(!parent)continue;
      const dx=(x-parent.x)/2;
      edges+='<path class="edge" d="M'+parent.x+' '+parent.y+' C'+(parent.x+dx)+' '+parent.y+' '+(x-dx)+' '+y+' '+x+' '+y+'"/>';
    }
    const current=n.sha===state.sha;
    const refs=state.refs.filter(r=>r.sha===n.sha).map(r=>r.name);
    const date=n.committed.slice(0,10);
    const title=escape(n.subject+" | "+date+" | "+n.sha+" | "+(refs.join(", ")||"sem referência"));
    circles+='<g class="node '+(current?'current':'')+'" role="button" tabindex="0" data-sha="'+n.sha+'" transform="translate('+x+','+y+')"><title>'+title+'</title><circle r="6"/><text text-anchor="middle" y="'+(maxLane*24+45-y)+'">'+escape(n.short)+'</text></g>';
  }
  // Calendar labels give the graph an explicit chronological reference without
  // falsely claiming that topology and author timestamps are always monotonic.
  let axis="";
  for(let i=0;i<ordered.length;i+=Math.max(1,Math.ceil(ordered.length/15))){
    const n=ordered[i],x=positions.get(n.sha).x;
    axis+='<text x="'+x+'" y="'+(height-7)+'" text-anchor="middle">'+escape(n.committed.slice(0,10))+'</text>';
  }
  graphEl.innerHTML=edges+circles+'<g class="date-axis">'+axis+'</g>';
  document.querySelectorAll("[data-sha]").forEach(n=>{
    n.onclick=()=>revision(n.dataset.sha);
    n.onkeydown=e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();revision(n.dataset.sha)}};
  });
  if(!graphEl.dataset.initialized){
    graphEl.parentElement.scrollLeft=graphEl.parentElement.scrollWidth;
    graphEl.dataset.initialized="1";
  }else{
    graphEl.parentElement.scrollLeft=former;
  }
}
function render(){graph();$("selected-rev").textContent=state.sha.slice(0,8)+" · "+(state.sha===state.head?"HEAD":"histórico");$("map").hidden=state.view!=="map";$("management").hidden=state.view==="map";for(const key of ["home","domains","features"])$(key).classList.toggle("active",state.view===(key==="home"?"map":key));$("view-name").textContent=state.view==="map"?"Mapa geral":state.view==="domains"?"Gestão de domínios":"Gestão de features";renderMap();if(state.view!=="map")renderManagement()}
function renderMap(){let visible=cols.filter(c=>c.visible);$("grid").innerHTML='<colgroup>'+visible.map(c=>'<col style="width:'+c.width+'px">').join("")+'</colgroup><thead><tr>'+visible.map(c=>'<th class="'+(c.id==="domain"?"frozen":"")+'"><div class="column-head">'+escape(c.name)+'<span style="display:flex">'+(c.id==="domain"?"":'<button data-hide="'+c.id+'" title="Ocultar">−</button>')+'<span class="resize" data-size="'+c.id+'"></span></span></div></th>').join("")+'</tr></thead><tbody>'+state.domains.map(d=>'<tr>'+visible.map(c=>'<td class="'+(c.id==="domain"?"frozen":"")+'">'+(c.id==="domain"?'<button class="domain-link" data-domain="'+escape(d.id)+'">'+escape(d.domain)+'</button>':escape(d[c.id]))+'</td>').join("")+'</tr>').join("")+'</tbody>';$("picker").innerHTML=cols.map(c=>'<label><input type="checkbox" data-toggle="'+c.id+'" '+(c.visible?"checked":"")+' '+(c.id==="domain"?"disabled":"")+'> '+escape(c.name)+'</label>').join("");document.querySelectorAll("[data-domain]").forEach(b=>b.onclick=()=>navigate("domains",b.dataset.domain));document.querySelectorAll("[data-hide]").forEach(b=>b.onclick=()=>{cols.find(c=>c.id===b.dataset.hide).visible=false;renderMap()});document.querySelectorAll("[data-toggle]").forEach(b=>b.onchange=()=>{cols.find(c=>c.id===b.dataset.toggle).visible=b.checked;renderMap()});document.querySelectorAll("[data-size]").forEach(el=>el.onpointerdown=e=>{e.preventDefault();const col=cols.find(c=>c.id===el.dataset.size),x=e.clientX,old=col.width;const move=ev=>{col.width=Math.max(80,Math.min(550,old+ev.clientX-x));const idx=cols.filter(c=>c.visible).indexOf(col);$("grid").querySelectorAll("col")[idx].style.width=col.width+"px"};const stop=()=>{window.removeEventListener("pointermove",move);window.removeEventListener("pointerup",stop)};window.addEventListener("pointermove",move);window.addEventListener("pointerup",stop)})}
function renderTree(n){return '<details open><summary>'+escape(n.title)+'</summary>'+(n.body.trim()?'<pre>'+escape(n.body.trim())+'</pre>':"")+(n.children||[]).map(renderTree).join("")+'</details>'}
function renderManagement(){const arr=state.view==="domains"?state.domains.map(d=>({...d,name:d.domain})):state.features;if(!arr.some(i=>i.id===state.selected))state.selected=arr[0]?.id||null;const d=arr.find(i=>i.id===state.selected);$("side-features").classList.toggle("active",state.view==="features");$("side-domains").classList.toggle("active",state.view==="domains");$("items").innerHTML=arr.map(i=>'<button class="item '+(i.id===state.selected?"active":"")+'" data-item="'+escape(i.id)+'"><strong>'+escape(i.name)+'</strong><br><small class="muted">'+escape(i.purpose||i.path||"")+'</small></button>').join("");document.querySelectorAll("[data-item]").forEach(b=>b.onclick=()=>{state.selected=b.dataset.item;state.tab="description";renderManagement()});if(!d){$("item-title").textContent="Nenhum item nesta revisão";$("item-description").textContent="";$("item-state").textContent="";$("detail").innerHTML="";return}$("item-title").textContent=d.name;$("item-description").textContent=d.purpose||"";$("item-state").textContent=d.status;document.querySelectorAll("[data-tab]").forEach(b=>b.classList.toggle("active",b.dataset.tab===state.tab));const detail=$("detail");if(state.tab==="description"&&d.path){detail.textContent="Carregando documentação…";get("/api/document?sha="+state.sha+"&path="+encodeURIComponent(d.path)).then(doc=>{if(state.tab==="description"&&state.selected===d.id)detail.innerHTML='<div class="tree">'+renderTree(doc.tree)+'</div>'}).catch(e=>detail.textContent=e.message)}else if(state.tab==="description")detail.innerHTML='<div class="tree"><details open><summary>Propósito</summary><p>'+escape(d.purpose)+'</p></details><details><summary>É dono de</summary><p>'+escape(d.owns)+'</p></details><details><summary>Não pode tocar</summary><p>'+escape(d.excludes)+'</p></details><details><summary>Invariantes</summary><p>'+escape(d.invariants)+'</p></details></div><p class="muted">Catálogo candidato; historicidade não estabelecida.</p>';else if(state.tab==="events")detail.innerHTML='<div class="tree"><b>Entradas</b><p>'+escape(d.inputs||"Não catalogadas")+'</p><b>Saídas</b><p>'+escape(d.outputs||"Não catalogadas")+'</p><p class="muted">Nomes propostos, não verificados no código.</p></div>';else detail.innerHTML='<p class="muted">'+(state.tab==="code"?"Indexação de código ainda não implementada.":"Nenhum teste executado nesta versão.")+'</p>'}
$("home").onclick=()=>navigate("map");$("domains").onclick=$("side-domains").onclick=()=>navigate("domains");$("features").onclick=$("side-features").onclick=()=>navigate("features");document.querySelectorAll("[data-tab]").forEach(b=>b.onclick=()=>{state.tab=b.dataset.tab;renderManagement()});$("columns").onclick=()=>{$("picker").hidden=!$("picker").hidden};$("reset").onclick=()=>{cols.forEach(c=>c.visible=true);renderMap()};$("theme").onclick=()=>{document.body.classList.toggle("dark");localStorage.setItem("wd-theme",document.body.classList.contains("dark")?"dark":"light")};if(localStorage.getItem("wd-theme")==="dark")document.body.classList.add("dark");load();