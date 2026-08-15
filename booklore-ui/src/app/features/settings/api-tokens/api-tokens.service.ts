import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';

export interface ApiTokenSummary {
  id: number;
  name: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface ApiTokenCreatedResponse {
  id: number;
  name: string;
  /** Only ever present on the create response - never retrievable again after this. */
  token: string;
  createdAt: string;
}

@Injectable({
  providedIn: 'root'
})
export class ApiTokensService {

  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/api-tokens`;
  private http = inject(HttpClient);

  list(): Observable<ApiTokenSummary[]> {
    return this.http.get<ApiTokenSummary[]>(this.baseUrl);
  }

  create(name: string): Observable<ApiTokenCreatedResponse> {
    return this.http.post<ApiTokenCreatedResponse>(this.baseUrl, {name});
  }

  revoke(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
