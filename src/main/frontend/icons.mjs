// Heroicons (outline, 24px) rendered inline. Markup anywhere in the app asks for an icon with
// <i data-icon="name"></i>; renderIcons() swaps each placeholder for the matching SVG.
// Only the icons listed here are bundled, so add the file to this map to use a new one.
import archiveBox from 'heroicons/24/outline/archive-box.svg';
import adjustmentsHorizontal from 'heroicons/24/outline/adjustments-horizontal.svg';
import arrowDownTray from 'heroicons/24/outline/arrow-down-tray.svg';
import arrowPath from 'heroicons/24/outline/arrow-path.svg';
import arrowRightEndOnRectangle from 'heroicons/24/outline/arrow-right-end-on-rectangle.svg';
import arrowRightStartOnRectangle from 'heroicons/24/outline/arrow-right-start-on-rectangle.svg';
import arrowUp from 'heroicons/24/outline/arrow-up.svg';
import arrowUpRight from 'heroicons/24/outline/arrow-up-right.svg';
import arrowUpTray from 'heroicons/24/outline/arrow-up-tray.svg';
import arrowsUpDown from 'heroicons/24/outline/arrows-up-down.svg';
import bookOpen from 'heroicons/24/outline/book-open.svg';
import bars3 from 'heroicons/24/outline/bars-3.svg';
import bugAnt from 'heroicons/24/outline/bug-ant.svg';
import calendarDays from 'heroicons/24/outline/calendar-days.svg';
import chartPie from 'heroicons/24/outline/chart-pie.svg';
import check from 'heroicons/24/outline/check.svg';
import checkCircle from 'heroicons/24/outline/check-circle.svg';
import chevronDown from 'heroicons/24/outline/chevron-down.svg';
import chevronLeft from 'heroicons/24/outline/chevron-left.svg';
import chevronRight from 'heroicons/24/outline/chevron-right.svg';
import clock from 'heroicons/24/outline/clock.svg';
import cloudArrowUp from 'heroicons/24/outline/cloud-arrow-up.svg';
import codeBracketSquare from 'heroicons/24/outline/code-bracket-square.svg';
import cog6Tooth from 'heroicons/24/outline/cog-6-tooth.svg';
import document_ from 'heroicons/24/outline/document.svg';
import documentDuplicate from 'heroicons/24/outline/document-duplicate.svg';
import documentText from 'heroicons/24/outline/document-text.svg';
import ellipsisHorizontal from 'heroicons/24/outline/ellipsis-horizontal.svg';
import eye from 'heroicons/24/outline/eye.svg';
import folder from 'heroicons/24/outline/folder.svg';
import folderArrowDown from 'heroicons/24/outline/folder-arrow-down.svg';
import folderOpen from 'heroicons/24/outline/folder-open.svg';
import folderPlus from 'heroicons/24/outline/folder-plus.svg';
import globeAlt from 'heroicons/24/outline/globe-alt.svg';
import informationCircle from 'heroicons/24/outline/information-circle.svg';
import key from 'heroicons/24/outline/key.svg';
import link from 'heroicons/24/outline/link.svg';
import linkSlash from 'heroicons/24/outline/link-slash.svg';
import listBullet from 'heroicons/24/outline/list-bullet.svg';
import magnifyingGlass from 'heroicons/24/outline/magnifying-glass.svg';
import pencil from 'heroicons/24/outline/pencil.svg';
import plus from 'heroicons/24/outline/plus.svg';
import serverStack from 'heroicons/24/outline/server-stack.svg';
import scale from 'heroicons/24/outline/scale.svg';
import shieldCheck from 'heroicons/24/outline/shield-check.svg';
import shieldExclamation from 'heroicons/24/outline/shield-exclamation.svg';
import squares2x2 from 'heroicons/24/outline/squares-2x2.svg';
import tableCells from 'heroicons/24/outline/table-cells.svg';
import trash from 'heroicons/24/outline/trash.svg';
import userCircle from 'heroicons/24/outline/user-circle.svg';
import userPlus from 'heroicons/24/outline/user-plus.svg';
import users from 'heroicons/24/outline/users.svg';
import xMark from 'heroicons/24/outline/x-mark.svg';

// Moon and sun for the theme toggle are not in the outline set under the same names.
import moon from 'heroicons/24/outline/moon.svg';
import sun from 'heroicons/24/outline/sun.svg';

export const ICONS = {
  'adjustments-horizontal': adjustmentsHorizontal,
  'archive-box': archiveBox,
  'arrow-down-tray': arrowDownTray,
  'arrow-path': arrowPath,
  'arrow-right-end-on-rectangle': arrowRightEndOnRectangle,
  'arrow-right-start-on-rectangle': arrowRightStartOnRectangle,
  'arrow-up': arrowUp,
  'arrow-up-right': arrowUpRight,
  'arrow-up-tray': arrowUpTray,
  'arrows-up-down': arrowsUpDown,
  'bars-3': bars3,
  'book-open': bookOpen,
  'bug-ant': bugAnt,
  'calendar-days': calendarDays,
  'chart-pie': chartPie,
  check,
  'check-circle': checkCircle,
  'chevron-down': chevronDown,
  'chevron-left': chevronLeft,
  'chevron-right': chevronRight,
  clock,
  'cloud-arrow-up': cloudArrowUp,
  'code-bracket-square': codeBracketSquare,
  'cog-6-tooth': cog6Tooth,
  document: document_,
  'document-duplicate': documentDuplicate,
  'document-text': documentText,
  'ellipsis-horizontal': ellipsisHorizontal,
  eye,
  folder,
  'folder-arrow-down': folderArrowDown,
  'folder-open': folderOpen,
  'folder-plus': folderPlus,
  'globe-alt': globeAlt,
  'information-circle': informationCircle,
  key,
  link,
  'link-slash': linkSlash,
  'list-bullet': listBullet,
  'magnifying-glass': magnifyingGlass,
  moon,
  pencil,
  plus,
  'server-stack': serverStack,
  scale,
  'shield-check': shieldCheck,
  'shield-exclamation': shieldExclamation,
  'squares-2x2': squares2x2,
  sun,
  'table-cells': tableCells,
  trash,
  'user-circle': userCircle,
  'user-plus': userPlus,
  users,
  'x-mark': xMark,
};

const SVG_NS = 'http://www.w3.org/2000/svg';
const template = typeof document === 'undefined' ? null : document.createElement('template');

/** The <svg> element for an icon name, or null when the name is unknown. */
export function iconElement(name) {
  const markup = ICONS[name];
  if (!markup || !template) return null;
  template.innerHTML = markup.trim();
  const svg = template.content.firstElementChild;
  return svg ? svg.cloneNode(true) : null;
}

/** Replaces every <i data-icon="…"> placeholder under `root` with its SVG, keeping its classes. */
export function renderIcons(root = document) {
  root.querySelectorAll('[data-icon]').forEach((placeholder) => {
    // An already rendered <svg> is only redone when its requested icon changed since.
    if (placeholder.namespaceURI === SVG_NS && placeholder.getAttribute('data-icon-rendered') === placeholder.getAttribute('data-icon')) return;
    const svg = iconElement(placeholder.getAttribute('data-icon'));
    if (!svg) {
      console.warn(`Unknown icon "${placeholder.getAttribute('data-icon')}"`);
      return;
    }
    svg.setAttribute('data-icon', placeholder.getAttribute('data-icon'));
    svg.setAttribute('data-icon-rendered', placeholder.getAttribute('data-icon'));
    svg.setAttribute('aria-hidden', 'true');
    // Heroicons carry no intrinsic size; CSS classes (size-4 etc.) override these attributes.
    svg.setAttribute('width', '24');
    svg.setAttribute('height', '24');
    svg.removeAttribute('data-slot');
    const classes = placeholder.getAttribute('class');
    if (classes) svg.setAttribute('class', classes);
    placeholder.replaceWith(svg);
  });
}
