// tailwindTheme.js
(function(){
const html=document.documentElement;
const current=localStorage.getItem('theme')||'light';
html.classList.toggle('dark',current==='dark');
let btn;
function apply(m){
 html.classList.toggle('dark',m==='dark');
 localStorage.setItem('theme',m);
 if(btn)btn.textContent=m==='dark'?'☀️':'🌙';
}
function init(){
 btn=document.getElementById('themeToggle');
 if(!btn)return;
 btn.addEventListener('click',()=>apply(html.classList.contains('dark')?'light':'dark'));
 btn.textContent=html.classList.contains('dark')?'☀️':'🌙';
}
if(document.readyState!=='loading')init();
else document.addEventListener('DOMContentLoaded',init);
})();
