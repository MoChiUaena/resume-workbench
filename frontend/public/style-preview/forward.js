const choice=new URLSearchParams(location.search).get('style');
if(choice==='a'||choice==='b')localStorage.setItem('rw-ui-theme',choice);
location.replace('/');
