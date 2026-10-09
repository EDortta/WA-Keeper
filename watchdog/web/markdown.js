"use strict";
// Small, dependency-free Markdown renderer for trusted UI structure and
// untrusted repository text. HTML is always escaped, URLs are allowlisted.
const MarkdownView=(()=>{
  const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const safeUrl=url=>/^(https?:\/\/|mailto:|#|\.\.?\/)/i.test(url)?url:null;
  function inline(text){
    const saved=[];
    const stash=html=>{const token="\uE000"+saved.length+"\uE001";saved.push(html);return token};
    let s=String(text??"");
    s=s.replace(/`([^`\n]+)`/g,(_,code)=>stash('<code>'+esc(code)+'</code>'));
    s=s.replace(/\[([^\]\n]+)\]\(([^\s)]+)\)/g,(_,label,url)=>{
      const target=safeUrl(url);
      return target?stash('<a href="'+esc(target)+'" rel="noopener noreferrer" target="_blank">'+esc(label)+'</a>'):esc(label);
    });
    s=esc(s);
    s=s.replace(/\*\*([^*\n]+)\*\*|__([^_\n]+)__/g,(_,a,b)=>'<strong>'+(a||b)+'</strong>');
    s=s.replace(/(?<!\*)\*([^*\n]+)\*(?!\*)|(?<!_)_([^_\n]+)_(?!_)/g,(_,a,b)=>'<em>'+(a||b)+'</em>');
    s=s.replace(/~~([^~\n]+)~~/g,'<del>$1</del>');
    return s.replace(/\uE000(\d+)\uE001/g,(_,i)=>saved[+i]||"");
  }
  function blocks(source){
    const lines=String(source||"").split(/\r?\n/),out=[];let i=0;
    while(i<lines.length){
      const line=lines[i];
      if(!line.trim()){i++;continue}
      const fence=line.match(/^\s{0,3}(`{3,}|~{3,})([a-zA-Z0-9_+-]*)\s*$/);
      if(fence){
        const code=[],mark=fence[1][0],min=fence[1].length;
        i++;
        while(i<lines.length&&!new RegExp('^\\s{0,3}'+(mark==='~'?'~':'`')+'{'+min+',}\\s*$').test(lines[i]))code.push(lines[i++]);
        if(i<lines.length)i++;
        out.push('<pre><code>'+esc(code.join("\n"))+'</code></pre>');continue;
      }
      const h=line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/);
      if(h){out.push('<h'+h[1].length+'>'+inline(h[2])+'</h'+h[1].length+'>');i++;continue}
      if(/^\s{0,3}([-*_]\s*){3,}$/.test(line)){out.push('<hr>');i++;continue}
      if(/^\s{0,3}>\s?/.test(line)){
        const q=[];while(i<lines.length&&/^\s{0,3}>\s?/.test(lines[i]))q.push(lines[i++].replace(/^\s{0,3}>\s?/,""));
        out.push('<blockquote>'+blocks(q.join("\n"))+'</blockquote>');continue;
      }
      const item=line.match(/^\s{0,3}([-+*]|\d+\.)\s+(.+)$/);
      if(item){
        const ordered=/\d/.test(item[1]),tag=ordered?"ol":"ul",elements=[];
        while(i<lines.length){
          const m=lines[i].match(/^\s{0,3}([-+*]|\d+\.)\s+(.+)$/);
          if(!m||/\d/.test(m[1])!==ordered)break;
          elements.push('<li>'+inline(m[2])+'</li>');i++;
        }
        out.push('<'+tag+'>'+elements.join("")+'</'+tag+'>');continue;
      }
      const paragraph=[];
      while(i<lines.length&&lines[i].trim()&&!/^\s{0,3}(#{1,6}\s|[-+*]\s|\d+\.\s|>|\`{3}|~{3})/.test(lines[i]))paragraph.push(lines[i++]);
      if(paragraph.length)out.push('<p>'+paragraph.map(inline).join('<br>')+'</p>');
      else{out.push('<p>'+inline(lines[i])+'</p>');i++}
    }
    return out.join("");
  }
  return {inline,blocks};
})();
