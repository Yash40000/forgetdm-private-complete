'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import type {
  ComplianceScan,
  CompliancePosture,
  EvidencePack,
  ExceptionRequest,
  PiiException,
  RunScanRequest,
  SubjectSearchRequest
} from './types';

const BASE = '/api/compliance';

export const complianceKeys = {
  posture: ['compliance', 'posture'] as const,
  scans: ['compliance', 'scans'] as const,
  scan: (id: number) => ['compliance', 'scan', id] as const,
  exceptions: ['compliance', 'exceptions'] as const
};

/**
 * Posture aggregates across the whole ledger, so it is the most expensive read on this page.
 * `keepPreviousData` is what stops the summary cards unmounting while a refetch is in flight —
 * without it the row of cards disappears and reappears on every window focus, which reads as a
 * flicker. `refetchOnWindowFocus: false` stops alt-tabbing from triggering that work at all.
 */
export function usePosture() {
  return useQuery({
    queryKey: complianceKeys.posture,
    queryFn: () => apiFetch<CompliancePosture>(`${BASE}/posture`),
    placeholderData: keepPreviousData,
    staleTime: 60_000,
    refetchOnWindowFocus: false
  });
}

export function useScans(limit = 50) {
  return useQuery({
    queryKey: [...complianceKeys.scans, limit],
    queryFn: () => apiFetch<ComplianceScan[]>(`${BASE}/scans?limit=${limit}`),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    refetchOnWindowFocus: false
  });
}

export function useScan(id: number | null) {
  return useQuery({
    queryKey: complianceKeys.scan(id ?? 0),
    queryFn: () => apiFetch<ComplianceScan>(`${BASE}/scans/${id}`),
    enabled: id != null,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    refetchOnWindowFocus: false
  });
}

export function useExceptions() {
  return useQuery({
    queryKey: complianceKeys.exceptions,
    queryFn: () => apiFetch<PiiException[]>(`${BASE}/exceptions`),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    refetchOnWindowFocus: false
  });
}

/** Registered environments, used to pick a scan target. Effectively static within a session. */
export function useComplianceDataSources() {
  return useQuery({
    queryKey: ['compliance', 'datasources'],
    queryFn: () => apiFetch<Array<{ id: number; name: string; kind?: string | null }>>('/api/datasources'),
    staleTime: 300_000,
    refetchOnWindowFocus: false
  });
}

/** Masking policies, used to judge coverage. */
export function useCompliancePolicies() {
  return useQuery({
    queryKey: ['compliance', 'policies'],
    queryFn: () => apiFetch<Array<{ id: number; name: string }>>('/api/policies'),
    staleTime: 300_000,
    refetchOnWindowFocus: false
  });
}

export function useComplianceMutations() {
  const qc = useQueryClient();
  const refreshScans = () => {
    void qc.invalidateQueries({ queryKey: complianceKeys.scans });
    void qc.invalidateQueries({ queryKey: complianceKeys.posture });
  };
  const refreshExceptions = () => {
    void qc.invalidateQueries({ queryKey: complianceKeys.exceptions });
    void qc.invalidateQueries({ queryKey: complianceKeys.posture });
  };

  const runScan = useMutation({
    mutationFn: (body: RunScanRequest) => apiPost<ComplianceScan>(`${BASE}/scans`, body),
    onSuccess: refreshScans
  });

  const subjectSearch = useMutation({
    mutationFn: (body: SubjectSearchRequest) => apiPost<ComplianceScan>(`${BASE}/subject-search`, body),
    onSuccess: refreshScans
  });

  const deleteScan = useMutation({
    mutationFn: (id: number) => apiFetch<{ deleted: number }>(`${BASE}/scans/${id}`, { method: 'DELETE' }),
    onSuccess: refreshScans
  });

  const requestException = useMutation({
    mutationFn: (body: ExceptionRequest) => apiPost<PiiException>(`${BASE}/exceptions`, body),
    onSuccess: refreshExceptions
  });

  const approveException = useMutation({
    mutationFn: (v: { id: number; note?: string }) =>
      apiPost<PiiException>(`${BASE}/exceptions/${v.id}/approve`, { note: v.note }),
    onSuccess: refreshExceptions
  });

  const rejectException = useMutation({
    mutationFn: (v: { id: number; reason: string }) =>
      apiPost<PiiException>(`${BASE}/exceptions/${v.id}/reject`, { reason: v.reason }),
    onSuccess: refreshExceptions
  });

  const revokeException = useMutation({
    mutationFn: (v: { id: number; reason?: string }) =>
      apiPost<PiiException>(`${BASE}/exceptions/${v.id}/revoke`, { reason: v.reason }),
    onSuccess: refreshExceptions
  });

  const deleteException = useMutation({
    mutationFn: (id: number) => apiFetch<{ deleted: number }>(`${BASE}/exceptions/${id}`, { method: 'DELETE' }),
    onSuccess: refreshExceptions
  });

  const buildEvidencePack = useMutation({
    mutationFn: (body: { targetId: number; sourceId?: number | null; policyId?: number | null; schemaName?: string | null }) =>
      apiPost<EvidencePack>(`${BASE}/evidence-pack`, body)
  });

  return {
    runScan,
    subjectSearch,
    deleteScan,
    requestException,
    approveException,
    rejectException,
    revokeException,
    deleteException,
    buildEvidencePack
  };
}
