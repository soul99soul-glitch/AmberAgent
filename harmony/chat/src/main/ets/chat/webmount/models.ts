// webmount/models — E12 共享命名 DTO(E12 冻结合同同名形状)
//
// 纯数据接口,无运行依赖;ArkTS 禁内联对象联合,一律命名 interface。

import type { JsonObject } from '../json.ts';
import type { UIMessagePartTool } from '../message.ts';

export interface WebMountDocumentToken { sessionId: string; documentId: string; revision: number; }

export interface WebMountElement {
  ref: string;
  tag: string;
  text: string;
  href: string | null;
  inputType: string | null;
  // visible option values only; empty unless a select candidate
  options: string[];
}

export interface WebMountObservation {
  token: WebMountDocumentToken;
  url: string;
  title: string;
  text: string;
  elements: WebMountElement[];
}

export interface WebMountOAuthApplication {
  siteId: string;
  bindingHash: string;
  authorizationEndpoint: string;
  tokenEndpoint: string;
  clientId: string;
  redirectUri: string;
  scope: string;
  tokenEncoding: 'form' | 'json';
  clientAuthentication: 'none' | 'body' | 'basic';
  apiOrigins: string[];
  clientSecretRef: string | null;
}

export interface WebMountOAuthApplicationDraft {
  application: WebMountOAuthApplication;
  secretAction: 'keep' | 'replace' | 'clear';
  clientSecret: string | null;
}

export type WebMountOAuthStatus =
  'unconfigured' | 'disconnected' | 'awaiting_callback' | 'connected' | 'expired' | 'failed';

export interface WebMountOAuthState {
  siteId: string;
  bindingHash: string | null;
  status: WebMountOAuthStatus;
  expiresAtMillis: number | null;
  errorCode: string | null;
  message: string | null;
}

export interface WebMountHarArchive { id: string; importedAtMillis: number; har: JsonObject; }

export interface WebMountReplayTemplate {
  id: string;
  sessionId: string;
  sourceId: string;
  source: 'page_fetch_xhr' | 'imported_har';
  documentId: string | null;
  origin: string;
  method: string;
  url: string;
}

export interface WebMountNetworkSummary {
  requestTemplateId: string;
  sourceId: string;
  captureCoverage: 'page_fetch_xhr' | 'imported_har';
  method: string;
  displayUrl: string;
  status: number | null;
  replayable: boolean;
  reason: string | null;
}

export interface WebMountSiteAdapterResult {
  siteId: string;
  observation: WebMountDocumentToken;
  url: string;
  fields: JsonObject;
  matched: number;
  truncated: boolean;
}

export interface WebMountGoalRequest {
  sessionId: string;
  goal: string;
  completionText: string;
  allowedActions: string[];
  draftValue: string | null;
  maxActionDecisions: number;
  maxSeconds: number;
  maxNoProgress: number;
  decisionSource: 'main_model' | 'jev';
}

export interface WebMountGoalDecision {
  kind: 'action' | 'handback';
  candidateId: string | null;
  reason: string;
}

export interface WebMountGoalCheckpoint {
  version: 1;
  request: WebMountGoalRequest;
  phase: 'ready' | 'awaiting_approval' | 'started' | 'finished';
  observation: WebMountObservation | null;
  pendingStep: UIMessagePartTool | null;
  decisions: number;
  noProgress: number;
  startedAtMillis: number;
  deadlineMillis: number;
}
