/**
 * The TROVE symbol: a cube whose left face is book spines and whose right face is server slots.
 * Vector rebuild of the approved brand sheet (the supplied PNG exports are small raster crops), on a
 * 124x124 box. The two status dots are holes, so render with fill-rule="evenodd".
 */
export const TROVE_MARK_VIEWBOX = '0 0 124 124';

export const TROVE_MARK_PATH =
  'M6.5 30.51Q6.5 27.91 8.78 26.67L25.72 17.42Q28 16.17 28 18.77L28 101.93Q28 104.53 25.73 103.27L8.77 93.87Q6.5 92.61 6.5 90.01ZM35.5 21.9Q35.5 19.3 37.78 20.55L47.72 26.02Q50 27.28 50 29.88L50 113.68Q50 116.28 47.72 115.02L37.78 109.55Q35.5 108.3 35.5 105.7ZM57.35 42.9L57.35 115.4A7.9 7.9 0 0 0 73.15 115.4L73.15 42.9A7.9 7.9 0 0 0 57.35 42.9ZM48.44 16.64L104.84 41.44A8.35 8.35 0 0 0 111.56 26.16L55.16 1.36A8.35 8.35 0 0 0 48.44 16.64ZM80.5 60.85Q80.5 58.25 82.91 57.26L114.09 44.48Q116.5 43.49 116.5 46.09L116.5 49.01Q116.5 51.61 114.21 52.84L82.79 69.71Q80.5 70.94 80.5 68.34ZM80.5 81.18Q80.5 78.58 82.78 77.33L114.22 60.1Q116.5 58.85 116.5 61.45L116.5 67.15Q116.5 69.75 114.23 71.01L82.77 88.43Q80.5 89.69 80.5 87.09ZM108.8 67.9a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0ZM80.5 99.41Q80.5 96.81 82.78 95.56L114.22 78.4Q116.5 77.15 116.5 79.75L116.5 86.39Q116.5 88.99 114.24 90.26L82.76 108.05Q80.5 109.33 80.5 106.73ZM108.8 87.1a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0Z';

/** Brand gradient, bottom-left to top-right. */
export const TROVE_GRADIENT_STOPS: ReadonlyArray<{offset: number; color: string}> = [
  {offset: 0, color: '#0a66ff'},
  {offset: 0.55, color: '#10a6ee'},
  {offset: 1, color: '#1ed3bf'},
];
