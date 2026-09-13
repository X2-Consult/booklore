import {Injectable} from '@angular/core';
import {TROVE_MARK_PATH, TROVE_MARK_VIEWBOX} from '../../../components/trove-mark/trove-mark';

@Injectable({providedIn: 'root'})
export class FaviconService {
  // The TROVE symbol in the theme's accent colour; the status dots are holes (evenodd).
  private svgTemplate = (color: string) =>
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${TROVE_MARK_VIEWBOX}">` +
    `<path d="${TROVE_MARK_PATH}" fill="${color}" fill-rule="evenodd"/></svg>`;

  updateFavicon(color: string) {
    const svg = this.svgTemplate(color);
    const blob = new Blob([svg], {type: 'image/svg+xml'});
    const url = URL.createObjectURL(blob);

    let favicon = document.querySelector("link[rel*='icon']") as HTMLLinkElement;
    if (!favicon) {
      favicon = document.createElement('link');
      favicon.rel = 'icon';
      document.head.appendChild(favicon);
    }

    favicon.type = 'image/svg+xml';
    favicon.href = url;
  }
}
