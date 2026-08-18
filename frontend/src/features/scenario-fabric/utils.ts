import type { JsonMap } from './types';

export function asMap(value: unknown): JsonMap {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as JsonMap)
    : {};
}

export function asList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

export function textValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : value == null ? fallback : String(value);
}

export function numberValue(value: unknown): number {
  if (typeof value === 'number') return value;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function listOfText(value: unknown): string[] {
  return asList(value).map((item) => textValue(item)).filter(Boolean);
}

export function statusColor(status: string) {
  if (['READY', 'READY_WITH_WARNINGS', 'COMPLETED', 'VERIFIED', 'PUBLISHED'].includes(status)) {
    return 'green';
  }
  if (['FAILED', 'CANCELLED', 'REJECTED'].includes(status)) return 'red';
  if (['RUNNING', 'APPROVED'].includes(status)) return 'blue';
  if (['NEEDS_BINDING', 'PENDING_APPROVAL', 'WAITING_APPROVAL'].includes(status)) return 'yellow';
  return 'gray';
}

export function formatWhen(value?: string | null) {
  if (!value) return 'Not yet';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function formatCount(value: number) {
  return new Intl.NumberFormat().format(value);
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}
