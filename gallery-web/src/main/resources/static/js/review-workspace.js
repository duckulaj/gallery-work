const Review=(()=>{let selected=new Set(),timer,poll;
 const ids=()=>[...selected];
 function toggle(e,card){if(e.target.closest('button,a,input,select'))return;const id=card.dataset.id;if(selected.has(id)){selected.delete(id);card.classList.remove('selected')}else{selected.add(id);card.classList.add('selected')}}
 function threshold(v){document.getElementById('thresholdOutput').value=(Number(v)/100).toFixed(2)}
 function query(){return new URLSearchParams({filter:document.getElementById('filter').value,threshold:(Number(document.getElementById('threshold').value)/100).toFixed(2),q:document.getElementById('search').value})}
 async function reload(){selected.clear();const r=await fetch('/review/grid?'+query());const html=await r.text();const d=new DOMParser().parseFromString(html,'text/html');document.getElementById('workspace').replaceWith(d.getElementById('workspace'));startPolling()}
 function debounceReload(){clearTimeout(timer);timer=setTimeout(reload,300)}
 async function post(url,body){const token=document.querySelector('meta[name="_csrf"]')?.content,header=document.querySelector('meta[name="_csrf_header"]')?.content;const headers={'Content-Type':'application/json'};if(token&&header)headers[header]=token;const r=await fetch(url,{method:'POST',headers,body:JSON.stringify(body)});if(!r.ok)throw new Error(await r.text());return r.json()}
 function requireSelection(){if(!selected.size){toast('Select one or more photos first');return false}return true}
 async function setStatus(status){if(!requireSelection())return;await post('/review/status',{ids:ids(),status});toast(`${selected.size} photo(s) updated`);reload()}
 async function queue(force){const chosen=ids();await post('/review/queue',{ids:chosen,force});toast('NSFW scan queued');startPolling()}
 async function quarantine(){if(!requireSelection())return;if(!confirm(`Move ${selected.size} selected photo(s) to quarantine?`))return;await post('/review/quarantine',{ids:ids()});toast('Photos moved to quarantine');reload()}
 async function restore(){if(!requireSelection())return;await post('/review/restore',{ids:ids()});toast('Photos restored');reload()}
 async function updateStats(){try{const t=(Number(document.getElementById('threshold').value)/100).toFixed(2),s=await (await fetch('/review/stats?threshold='+t)).json();const total=s.queue.pending+s.queue.running+s.queue.completed+s.queue.failed;const done=s.queue.completed+s.queue.failed;document.getElementById('queueProgress').style.width=(total?done/total*100:0)+'%';document.getElementById('scanLabel').textContent=`${s.queue.pending} pending • ${s.queue.running} processing • ${s.queue.failed} failed`;if(!s.queue.pending&&!s.queue.running)clearInterval(poll)}catch(e){console.debug(e)}}
 function startPolling(){clearInterval(poll);updateStats();poll=setInterval(updateStats,1500)}
 function toast(text){const t=document.getElementById('toast');t.textContent=text;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2400)}
 document.addEventListener('DOMContentLoaded',startPolling);
 return{toggle,threshold,reload,debounceReload,setStatus,queue,quarantine,restore};})();
