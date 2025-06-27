function initTheme() {
  const html=document.documentElement;
  const stored=localStorage.getItem('theme');
  const prefersDark=window.matchMedia('(prefers-color-scheme: dark)').matches;
  const apply=t=>t==='dark'?html.classList.add('dark'):html.classList.remove('dark');
  apply(stored??(prefersDark?'dark':'light'));
  const btn=document.getElementById('themeToggle');
  if(!btn)return;
  btn.textContent=html.classList.contains('dark')?'\u2600\uFE0F':'\u1F319';
  btn.addEventListener('click',()=>{
    const on=html.classList.toggle('dark');
    const theme=on?'dark':'light';
    localStorage.setItem('theme',theme);
    btn.textContent=on?'\u2600\uFE0F':'\u1F319';
  });
}
document.addEventListener('DOMContentLoaded',initTheme);
