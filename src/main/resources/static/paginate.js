/* One pagination implementation for browser preview and Chromium PDF. */
(async () => {
  const root = document.querySelector('#pages');
  let sheet, content, main;
  const cloneTemplate = id => document.getElementById(id).content.cloneNode(true);
  const page = () => {
    if (root.children.length >= 10) throw new Error('PAGE_LIMIT');
    sheet = document.createElement('article'); sheet.className = 'sheet';
    sheet.dataset.page = String(root.children.length + 1);
    content = document.createElement('div'); content.className = 'page-content';
    content.append(cloneTemplate(root.children.length ? 'continuation-header' : 'first-header'));
    main = document.createElement('main'); content.append(main);
    sheet.append(content, cloneTemplate('footer')); root.append(sheet);
  };
  const bottomLimit = s => Math.min(s.querySelector('.page-footer').getBoundingClientRect().top-8,
    s.getBoundingClientRect().bottom-parseFloat(getComputedStyle(s).paddingBottom));
  const fits = () => content.getBoundingClientRect().bottom < bottomLimit(sheet);
  const sectionShell = source => { const s=source.cloneNode(false); s.append(source.querySelector('h2').cloneNode(true)); main.append(s); return s; };
  const entryShell = (source, section) => {
    const entry=source.cloneNode(false); entry.append(source.querySelector('.entry-heading').cloneNode(true));
    const items=source.querySelector('ul,.paragraphs')?.cloneNode(false) || document.createElement('div');
    entry.append(items); section.append(entry); return {entry,items};
  };
  try {
    page();
    const family=getComputedStyle(document.body).getPropertyValue('--resume-font').trim();
    await Promise.all([document.fonts.load(`400 12px ${family}`), document.fonts.load(`700 12px ${family}`)]);
    await Promise.all(Array.from(document.images).map(image => image.decode()));
    await document.fonts.ready;
    for (const source of document.querySelectorAll('#source > section')) {
      if (source.dataset.break==='true' && main.children.length) page();
      const whole=source.cloneNode(true); main.append(whole);
      if (fits()) continue;
      whole.remove();
      let section=sectionShell(source);
      for (const originalEntry of source.querySelectorAll(':scope > .entry')) {
        const entire=originalEntry.cloneNode(true); section.append(entire);
        if (fits()) continue;
        entire.remove();
        let block=entryShell(originalEntry,section);
        const units=Array.from(originalEntry.querySelector('ul,.paragraphs')?.children || []);
        if (!units.length && !fits()) {
          block.entry.remove(); if(section.children.length===1) section.remove();
          page(); section=sectionShell(source); block=entryShell(originalEntry,section);
          if(!fits()) throw new Error('HEADING_TOO_TALL');
        }
        for (const unit of units) {
          const item=unit.cloneNode(true); block.items.append(item);
          if(fits()) continue;
          item.remove();
          if(!block.items.children.length) block.entry.remove();
          if(section.children.length===1) section.remove();
          page(); section=sectionShell(source); block=entryShell(originalEntry,section); block.items.append(item);
          if(!fits()) throw new Error('ITEM_TOO_TALL');
        }
      }
      if(!source.querySelector('.entry') && !fits()) { section.remove(); page(); section=sectionShell(source); }
    }
    root.querySelectorAll('.page-number').forEach((el,i) => el.textContent=`${i+1} / ${root.children.length}`);
    if(!Array.from(root.children).every(s=>s.querySelector('.page-content').getBoundingClientRect().bottom < bottomLimit(s))) throw new Error('OVERFLOW');
    window.__resumePages=root.children.length; window.__resumeReady=true;
  } catch (e) { window.__resumeError=String(e); document.querySelector('#layout-error').hidden=false; }
  window.parent.postMessage({type:'resume-layout',pages:root.children.length,error:window.__resumeError || null},window.location.origin);
})();
