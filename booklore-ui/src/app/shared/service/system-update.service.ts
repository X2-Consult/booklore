import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {BehaviorSubject, Observable} from 'rxjs';
import {API_CONFIG} from '../../core/config/api-config';

export interface SelfUpdateStatus {
  currentVersion: string;
  latestVersion: string;
  updateAvailable: boolean;
  selfUpdateSupported: boolean;
  inProgress: boolean;
}

@Injectable({providedIn: 'root'})
export class SystemUpdateService {

  private http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/system`;
  private readonly healthUrl = `${API_CONFIG.BASE_URL}/api/v1/healthcheck`;

  readonly updating$ = new BehaviorSubject<boolean>(false);

  getUpdateStatus(): Observable<SelfUpdateStatus> {
    return this.http.get<SelfUpdateStatus>(`${this.baseUrl}/update-status`);
  }

  /** Kicks off the server update, then flips {@link updating$} and polls until the server is back on a new version. */
  triggerUpdate(previousVersion: string): Observable<{started: boolean}> {
    const obs = this.http.post<{started: boolean}>(`${this.baseUrl}/update`, null);
    obs.subscribe({
      next: () => {
        this.updating$.next(true);
        this.pollUntilBack(previousVersion);
      },
      error: () => {
        // handled by the caller's subscribe; nothing to poll
      }
    });
    return obs;
  }

  private pollUntilBack(previousVersion: string): void {
    let sawDown = false;
    const startedAt = Date.now();
    const MAX_WAIT_MS = 10 * 60 * 1000;

    const tick = async () => {
      if (Date.now() - startedAt > MAX_WAIT_MS) {
        // Give up waiting; reload anyway so the user isn't stuck on the overlay forever.
        window.location.reload();
        return;
      }
      try {
        const res = await fetch(this.healthUrl, {cache: 'no-store'});
        if (!res.ok) {
          sawDown = true;
        } else {
          const body = await res.json().catch(() => null);
          const version = body?.data?.version ?? body?.version;
          if (sawDown || (version && version !== previousVersion)) {
            window.location.reload();
            return;
          }
        }
      } catch {
        sawDown = true; // connection refused while the service restarts
      }
      setTimeout(tick, 4000);
    };

    setTimeout(tick, 4000);
  }
}
