// tailwindTheme.js
(function(){
const html=document.documentElement;
const stored=localStorage.getItem('theme');
const prefersDark=matchMedia('(prefers-color-scheme: dark)').matches;
let btn;
const apply=m=>{html.classList.toggle('dark',m==='dark');localStorage.setItem('theme',m);if(btn)btn.textContent=m==='dark'?'☀️':'🌙';};
const current=stored||(prefersDark?'dark':'light');
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
