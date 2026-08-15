import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {DatePipe} from '@angular/common';

import {Button} from 'primeng/button';
import {InputText} from 'primeng/inputtext';
import {Tooltip} from 'primeng/tooltip';
import {Dialog} from 'primeng/dialog';
import {FormsModule} from '@angular/forms';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {ConfirmationService, MessageService, PrimeTemplate} from 'primeng/api';
import {ApiTokenCreatedResponse, ApiTokenSummary, ApiTokensService} from './api-tokens.service';
import {catchError, takeUntil} from 'rxjs/operators';
import {of, Subject} from 'rxjs';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';

@Component({
  selector: 'app-api-tokens-settings',
  imports: [
    Button,
    InputText,
    Tooltip,
    Dialog,
    FormsModule,
    ConfirmDialog,
    DatePipe,
    PrimeTemplate,
    TranslocoDirective,
    TranslocoPipe
  ],
  providers: [ConfirmationService],
  templateUrl: './api-tokens-settings.html',
  styleUrl: './api-tokens-settings.scss'
})
export class ApiTokensSettings implements OnInit, OnDestroy {

  private apiTokensService = inject(ApiTokensService);
  private confirmationService = inject(ConfirmationService);
  private messageService = inject(MessageService);
  private t = inject(TranslocoService);

  tokens: ApiTokenSummary[] = [];
  loading = true;

  showCreateDialog = false;
  newTokenName = '';

  showCreatedDialog = false;
  createdToken: ApiTokenCreatedResponse | null = null;

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.loadTokens();
  }

  private loadTokens(): void {
    this.loading = true;
    this.apiTokensService.list().pipe(
      takeUntil(this.destroy$),
      catchError(err => {
        console.error('Error loading API tokens:', err);
        this.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsApiTokens.loadError'));
        return of([]);
      })
    ).subscribe(tokens => {
      this.tokens = tokens;
      this.loading = false;
    });
  }

  createToken(): void {
    if (!this.newTokenName.trim()) return;

    this.apiTokensService.create(this.newTokenName.trim()).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: created => {
        this.tokens.unshift({id: created.id, name: created.name, createdAt: created.createdAt, lastUsedAt: null});
        this.showCreateDialog = false;
        this.newTokenName = '';
        this.createdToken = created;
        this.showCreatedDialog = true;
        this.showMessage('success', this.t.translate('common.success'), this.t.translate('settingsApiTokens.createSuccess'));
      },
      error: err => {
        console.error('Error creating API token:', err);
        const message = err?.error?.message || this.t.translate('settingsApiTokens.createError');
        this.showMessage('error', this.t.translate('common.error'), message);
      }
    });
  }

  cancelCreate(): void {
    this.showCreateDialog = false;
    this.newTokenName = '';
  }

  closeCreatedDialog(): void {
    this.showCreatedDialog = false;
    this.createdToken = null;
  }

  copyCreatedToken(): void {
    if (!this.createdToken) return;
    navigator.clipboard.writeText(this.createdToken.token).then(() => {
      this.showMessage('success', this.t.translate('common.success'), this.t.translate('settingsApiTokens.tokenCopied'));
    });
  }

  confirmRevoke(token: ApiTokenSummary): void {
    this.confirmationService.confirm({
      message: this.t.translate('settingsApiTokens.revokeConfirm', {name: token.name}),
      header: this.t.translate('settingsApiTokens.revokeHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.revokeToken(token)
    });
  }

  private revokeToken(token: ApiTokenSummary): void {
    this.apiTokensService.revoke(token.id).pipe(
      takeUntil(this.destroy$),
      catchError(err => {
        console.error('Error revoking API token:', err);
        this.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsApiTokens.revokeError'));
        return of(null);
      })
    ).subscribe(() => {
      this.tokens = this.tokens.filter(t => t.id !== token.id);
      this.showMessage('success', this.t.translate('common.success'), this.t.translate('settingsApiTokens.revokeSuccess'));
    });
  }

  private showMessage(severity: string, summary: string, detail: string): void {
    this.messageService.add({severity, summary, detail});
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
