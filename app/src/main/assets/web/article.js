// Ark article helpers
function arkImgFail(img) {
  var f = img.closest('figure');
  if (f) f.classList.add('missing'); else img.style.display = 'none';
  var g = img.closest('.gallery');
  if (g && !g.querySelector('figure:not(.missing)')) g.style.display = 'none';
  var ib = img.closest('aside.infobox');
}
document.addEventListener('DOMContentLoaded', function () {
  // open the table of contents on wide screens
  if (window.innerWidth > 700) { var t = document.querySelector('details.toc'); if (t) t.open = true; }
});
