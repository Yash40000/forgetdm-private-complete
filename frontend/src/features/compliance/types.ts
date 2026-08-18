export type Severity = 'FAIL' | 'WARN' | 'INFO';
export type ScanResult = 'PASS' | 'WARN' | 'FAIL';
export type ScanType = 'FULL' | 'COVERAGE' | 'LEAK' | 'CARDINALITY' | 'SUBJECT';
export type ExceptionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED' | 'EXPIRED';

export interface ComplianceFinding {
  id: number;
  severity: Severity;
  check: string;
  schema: string | null;
  table: string | null;
  column: string | null;
  piiType: string | null;
  affectedRows: number;
  detail: string;
  remediation: string | null;
  evidenceHash: string | null;
}

export interface ComplianceScan {
  id: number;
  scanType: ScanType;
  name: string | null;
  environment: string | null;
  targetDataSourceId: number | null;
  targetName: string | null;
  sourceDataSourceId: number | null;
  sourceName: string | null;
  policyId: number | null;
  schemaName: string | null;
  subjectValueHash: string | null;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  result: ScanResult | null;
  columnsScanned: number;
  rowsScanned: number;
  failCount: number;
  warnCount: number;
  summary: string | null;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  owner: string | null;
  findings?: ComplianceFinding[];
}

export interface PiiException {
  id: number;
  dataSourceId: number;
  dataSourceName: string | null;
  environment: string;
  scope: string;
  piiType: string | null;
  justification: string;
  compensatingControls: string | null;
  requestedBy: string;
  approvedBy: string | null;
  approvedAt: string | null;
  expiresAt: string | null;
  status: ExceptionStatus;
  expired: boolean;
  daysRemaining: number | null;
  createdAt: string | null;
}

export interface CompliancePosture {
  scanCount: number;
  failing: number;
  warning: number;
  passing: number;
  lastScanAt: string | null;
  exceptionsTotal: number;
  exceptionsPending: number;
  exceptionsApproved: number;
  exceptionsExpired: number;
  auditChain: boolean | null;
  recent: ComplianceScan[];
}

export interface EvidencePack {
  generatedAt: string;
  generatedBy: string;
  environment: string;
  productionSource: string | null;
  policy: string | null;
  schemaName: string | null;
  piiFieldCount: number;
  coveredFieldCount: number;
  uncoveredFieldCount: number;
  coveragePercent: number;
  scanCount: number;
  exceptionCount: number;
  auditChainValid: boolean | null;
  markdown: string;
}

export interface RunScanRequest {
  scanType: ScanType;
  targetId: number;
  sourceId?: number | null;
  policyId?: number | null;
  schemaName?: string | null;
  environment?: string | null;
  name?: string | null;
}

export interface SubjectSearchRequest {
  subjectValue: string;
  piiType?: string | null;
  targetId?: number | null;
}

export interface ExceptionRequest {
  dataSourceId: number;
  environment?: string | null;
  scope: string;
  piiType?: string | null;
  justification: string;
  compensatingControls?: string | null;
  days?: number | null;
}
