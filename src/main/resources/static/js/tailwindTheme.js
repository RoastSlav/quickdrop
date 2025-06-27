// tailwindTheme.js
(function(){
const html=document.documentElement;
const stored=localStorage.getItem('theme');
let btn;
const apply=m=>{html.classList.toggle('dark',m==='dark');localStorage.setItem('theme',m);if(btn)btn.textContent=m==='dark'?'☀️':'🌙';};
// default to light when no preference has been stored
const system=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
const current=stored||'light';
html.classList.toggle('dark',current==='dark');
const init=()=>{
btn=document.getElementById('themeToggle');
if(!btn)return;
btn.addEventListener('click',()=>{
apply(html.classList.contains('dark')?'light':'dark');
});
btn.textContent=html.classList.contains('dark')?'☀️':'🌙';
};
document.addEventListener('DOMContentLoaded',init);
init();
})();
