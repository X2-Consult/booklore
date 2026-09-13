import {ChangeDetectionStrategy, Component, input} from '@angular/core';
import {TROVE_GRADIENT_STOPS, TROVE_MARK_PATH, TROVE_MARK_VIEWBOX} from './trove-mark';

let nextId = 0;

/**
 * The TROVE symbol. `accent` (default) fills it with the current CSS `color`, so it follows the
 * theme's accent the way the brand's dynamic-accent system intends; `gradient` is the fixed brand
 * gradient for places that show the brand itself. Size it with CSS on the host element.
 */
@Component({
  selector: 'app-trove-mark',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {role: 'img', 'aria-label': 'Trove'},
  styles: [`:host { display: inline-block; line-height: 0; } svg { width: 100%; height: 100%; }`],
  template: `
    <svg [attr.viewBox]="viewBox" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">
      @if (variant() === 'gradient') {
        <defs>
          <linearGradient [attr.id]="gradientId" gradientUnits="userSpaceOnUse" x1="6" y1="118" x2="118" y2="10">
            @for (stop of stops; track stop.offset) {
              <stop [attr.offset]="stop.offset" [attr.stop-color]="stop.color"/>
            }
          </linearGradient>
        </defs>
        <path [attr.d]="path" fill-rule="evenodd" [attr.fill]="'url(#' + gradientId + ')'"/>
      } @else {
        <path [attr.d]="path" fill-rule="evenodd" fill="currentColor"/>
      }
    </svg>
  `,
})
export class TroveMarkComponent {
  readonly variant = input<'accent' | 'gradient'>('accent');

  protected readonly viewBox = TROVE_MARK_VIEWBOX;
  protected readonly path = TROVE_MARK_PATH;
  protected readonly stops = TROVE_GRADIENT_STOPS;
  // Unique per instance: several logos can be on one page, and a gradient defined inside a hidden
  // SVG doesn't render for the others that reference the same id.
  protected readonly gradientId = `trove-gradient-${nextId++}`;
}
