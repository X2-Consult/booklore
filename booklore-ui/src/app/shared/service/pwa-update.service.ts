import {ApplicationRef, Injectable, inject} from '@angular/core';
import {SwUpdate, VersionReadyEvent} from '@angular/service-worker';
import {concat, interval} from 'rxjs';
import {filter, first} from 'rxjs/operators';

/**
 * Keeps the PWA's installed build in sync with what's deployed.
 *
 * Angular's service worker downloads a new version in the background but
 * never activates it on its own, so a long-lived session (exactly how this
 * app is used as an installed PWA) can keep running a stale bundle
 * indefinitely unless the user fully closes and reopens it. This service
 * watches for a new version becoming ready and reloads automatically, and
 * periodically polls for updates so a session left open for a long time
 * still picks up a new deploy.
 */
@Injectable({providedIn: 'root'})
export class PwaUpdateService {
  private static readonly CHECK_INTERVAL_MS = 30 * 60 * 1000;

  private swUpdate = inject(SwUpdate);
  private appRef = inject(ApplicationRef);

  constructor() {
    if (!this.swUpdate.isEnabled) {
      return;
    }

    this.swUpdate.versionUpdates
      .pipe(filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY'))
      .subscribe(() => this.reloadOntoNewVersion());

    this.swUpdate.unrecoverable.subscribe(() => this.reloadOntoNewVersion());

    // Once the app has settled, and then on an interval, ask the service
    // worker to check for a new version. Without this, updates are only
    // detected on the next full navigation, which an already-open PWA
    // session may not trigger for a very long time.
    const appIsStable$ = this.appRef.isStable.pipe(first(stable => stable));
    concat(appIsStable$, interval(PwaUpdateService.CHECK_INTERVAL_MS))
      .subscribe(() => void this.swUpdate.checkForUpdate());
  }

  private reloadOntoNewVersion(): void {
    this.swUpdate.activateUpdate()
      .catch(() => void 0)
      .finally(() => document.location.reload());
  }
}
