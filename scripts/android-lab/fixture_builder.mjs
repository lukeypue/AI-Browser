import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
export function buildFixtures({origin='https://reports.ryzod.com'}={}) {
 if(origin!=='https://reports.ryzod.com') throw new Error('only dedicated HTTPS fixture origin allowed');
 const files={};
 for(const style of ['classifieds','cars','marketplace','offerup']) {
  const drawer=['marketplace','offerup'].includes(style);
  files[style+'/index.html']=`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Synthetic Android Lab - ${style}</title><link rel="icon" href="data:,"><style>body{font:18px sans-serif;margin:20px;max-width:800px}input,button,select{font:inherit;margin:8px;padding:10px}a.card{display:block;border:1px solid #999;padding:24px;margin:12px 0;min-height:100px}aside[hidden],section[hidden]{display:none}aside{background:#eee;padding:12px}header{background:#def;padding:10px}</style><header><h1>Synthetic Android Lab</h1><p>${style} fixture. Generated content only.</p></header><main data-style="${style}"><form id="search" method="get"><label>Search<input type="search" name="q" aria-label="Search" placeholder="Search"></label><button type="submit">Search</button></form>${drawer?'<button id="filters" type="button">Filters</button>':''}<aside id="facets" ${drawer?'hidden':''}><form id="filterform"><label>Maximum price<input name="price_max" type="number" min="0" aria-label="Maximum price"></label><label>Sort by<select name="sort"><option value="default">Recommended</option><option value="price">Price low to high</option></select></label><button type="submit">Apply filters</button></form></aside><section id="results" aria-label="Search results"></section><button id="more" type="button">Load more</button><section id="detail" hidden></section></main><script src="../shared.js"></script></html>`;
 }
 files['shared.js']=`'use strict';
 const params=new URLSearchParams(location.search);const q=(params.get('q')||'mountain bike').slice(0,80);const item=params.get('item');const root=document.querySelector('main');
 const search=document.querySelector('input[name=q]');search.value=q;
 const price=document.querySelector('input[name=price_max]');price.value=params.get('price_max')||'';
 document.querySelector('select[name=sort]').value=params.get('sort')==='price'?'price':'default';
 let count=4;
 function render(){const box=document.querySelector('#results');box.replaceChildren();for(let i=0;i<count;i++){const amount=100+i*50;if(price.value && amount>Number(price.value))continue;const a=document.createElement('a');a.className='card';const link=new URL(location.href);link.searchParams.set('item',String(i+1));a.href=link.href;const title=document.createElement('h2');title.textContent=q+' '+(i+1);const value=document.createElement('p');value.textContent='$'+amount;a.append(title,value);box.append(a);}}
 document.querySelector('#filters')?.addEventListener('click',()=>{document.querySelector('#facets').hidden=!document.querySelector('#facets').hidden;});
 document.querySelector('#filterform').addEventListener('submit',event=>{event.preventDefault();const u=new URL(location.href);if(price.value)u.searchParams.set('price_max',price.value);else u.searchParams.delete('price_max');u.searchParams.set('sort',document.querySelector('select[name=sort]').value);u.searchParams.delete('item');location.assign(u.href);});
 document.querySelector('#more').addEventListener('click',()=>{count=Math.min(12,count+4);render();if(count===12){document.querySelector('#more').disabled=true;document.querySelector('#more').textContent='End of results';}});
 if(item && /^[1-9][0-9]?$/.test(item)){document.querySelector('#search').hidden=true;document.querySelector('#facets').hidden=true;document.querySelector('#filters')?.remove();document.querySelector('#results').hidden=true;document.querySelector('#more').hidden=true;const detail=document.querySelector('#detail');detail.hidden=false;const h=document.createElement('h1');h.textContent='Synthetic listing details';const title=document.createElement('p');title.textContent=q+' '+item;const facts=document.createElement('p');facts.textContent='Price: $'+(100+(Number(item)-1)*50)+'. Condition: good. Synthetic listing details: demonstration item for browser training. Listed in the generated Practice City catalogue. No real seller or account is involved. Local pickup is a simulated option; this page never sends messages or purchases.';const back=document.createElement('a');const u=new URL(location.href);u.searchParams.delete('item');back.href=u.href;back.textContent='Back to results';const button=document.createElement('button');button.textContent='Show description';const desc=document.createElement('p');desc.textContent='Synthetic condition: good.';desc.hidden=true;button.addEventListener('click',()=>{desc.hidden=false;button.hidden=true;});detail.append(h,title,facts,back,button,desc);}else render();`;
 const hashes=Object.fromEntries(Object.keys(files).sort().map(k=>[k,createHash('sha256').update(files[k]).digest('hex')]));
 files['manifest.json']=JSON.stringify({schema_version:1,content:'SYNTHETIC_FIXTURES',files:hashes})+'\n';
 return files;
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 const out=resolve(process.argv[2]||new URL('./fixtures',import.meta.url).pathname);
 for(const [name,body] of Object.entries(buildFixtures())){const path=resolve(out,name);mkdirSync(resolve(path,'..'),{recursive:true});writeFileSync(path,body);}
}
