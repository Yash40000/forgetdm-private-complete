'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Divider,
  Group,
  Loader,
  Modal,
  Select,
  Stack,
  Text,
  TextInput,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconCheck,
  IconChevronDown,
  IconChevronRight,
  IconDatabaseSearch,
  IconEye,
  IconGitBranch,
  IconRefresh,
  IconShieldCheck,
  IconSparkles,
  IconX
} from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { useDiscoveryColumnReview, useMaskFunctions, usePiiTypes } from '@/features/pii-discovery/hooks';
import type { DiscoveryColumnReviewRow, DiscoveryFinding } from '@/features/pii-discovery/types';
import {
  compatibleFunctions,
  completePiiTypeCatalog,
  defaultFunctionForPii,
  paramLabels
} from '@/features/pii-discovery/utils';
import { fetchColumns, useSyntheticGenerators } from '@/features/synthetic/hooks';
import type { GeneratorSpec, ProfileResponse } from '@/features/synthetic/types';
import { apiPatch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataColumn, MaskPreview } from '@/lib/types';
import type { ArchitectureFieldRule, CrossDatabaseLink, LoadedApplication } from './types';

type Props = {
  opened: boolean;
  applications: LoadedApplication[];
  crossLinks: CrossDatabaseLink[];
  rules: ArchitectureFieldRule[];
  initialSelection: { sliceId: string; table: string } | null;
  readOnly: boolean;
  onClose: () => void;
  onSave: (rules: ArchitectureFieldRule[]) => void;
};

type AnalysisResult = {
  findings: DiscoveryFinding[];
  profile: ProfileResponse;
};

type PersistedFinding = {
  id?: number | null;
  tableName?: string | null;
  columnName?: string | null;
};

type FieldView = {
  column: string;
  dataType: string;
  nullable?: boolean;
  review?: DiscoveryColumnReviewRow;
  rule: ArchitectureFieldRule;
};

const DETERMINISTIC_LINK_FUNCTIONS = new Set([
  'FORMAT_PRESERVE',
  'CHARACTER_MAP',
  'HASH_LOOKUP',
  'DIRECT_LOOKUP',
  'BANK_ACCOUNT',
  'ABA_ROUTING',
  'CREDIT_CARD',
  'SSN'
]);

export function FieldIntelligenceWorkspace({
  opened,
  applications,
  crossLinks,
  rules,
  initialSelection,
  readOnly,
  onClose,
  onSave
}: Props) {
  const queryClient = useQueryClient();
  const [draftRules, setDraftRules] = useState<ArchitectureFieldRule[]>(rules);
  const [selectedSliceId, setSelectedSliceId] = useState('');
  const [selectedTable, setSelectedTable] = useState('');
  const [expandedField, setExpandedField] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [previewValues, setPreviewValues] = useState<Record<string, string>>({});
  const [previewResults, setPreviewResults] = useState<Record<string, MaskPreview>>({});
  const wasOpened = useRef(false);

  useEffect(() => {
    if (opened && !wasOpened.current) {
      const preferredApplication = applications.find((application) => application.id === initialSelection?.sliceId)
        || applications[0];
      const preferredTable = preferredApplication?.tables.find((table) => normalize(table) === normalize(initialSelection?.table))
        || preferredApplication?.tables[0]
        || '';
      setDraftRules(rules);
      setSelectedSliceId(preferredApplication?.id || '');
      setSelectedTable(preferredTable);
      setExpandedField(initialSelection?.table ? preferredTable : null);
      setSearch('');
      setPreviewValues({});
      setPreviewResults({});
    }
    wasOpened.current = opened;
  }, [applications, initialSelection, opened, rules]);

  const selectedApplication = useMemo(
    () => applications.find((application) => application.id === selectedSliceId) || null,
    [applications, selectedSliceId]
  );
  const effectiveTable = selectedApplication?.tables.some((table) => normalize(table) === normalize(selectedTable))
    ? selectedTable
    : selectedApplication?.tables[0] || '';

  const columnQuery = useQuery({
    queryKey: keys.dataSources.columns(selectedApplication?.dataSourceId, effectiveTable, selectedApplication?.schema),
    enabled: opened && Boolean(selectedApplication?.dataSourceId) && Boolean(effectiveTable),
    queryFn: () => selectedApplication
      ? fetchColumns(selectedApplication.dataSourceId, selectedApplication.schema, effectiveTable)
      : Promise.resolve([])
  });
  const reviewQuery = useDiscoveryColumnReview(
    opened ? selectedApplication?.dataSourceId || null : null,
    opened ? selectedApplication?.schema || null : null,
    opened ? effectiveTable || null : null,
    []
  );
  const piiTypesQuery = usePiiTypes();
  const maskFunctionsQuery = useMaskFunctions();
  const generatorsQuery = useSyntheticGenerators();

  const piiTypeOptions = useMemo(
    () => completePiiTypeCatalog(piiTypesQuery.data || []).map((value) => ({ value, label: value.replaceAll('_', ' ') })),
    [piiTypesQuery.data]
  );
  const generatorOptions = useMemo(() => generatorCatalogOptions(generatorsQuery.data || []), [generatorsQuery.data]);
  const fieldViews = useMemo(() => buildFieldViews(
    selectedApplication,
    effectiveTable,
    columnQuery.data || [],
    reviewQuery.data || [],
    draftRules,
    crossLinks
  ), [columnQuery.data, crossLinks, draftRules, effectiveTable, reviewQuery.data, selectedApplication]);
  const visibleFields = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return needle ? fieldViews.filter((field) => [
      field.column,
      field.dataType,
      field.rule.piiType,
      field.rule.maskFunction,
      field.rule.generator
    ].some((value) => normalize(value).includes(needle))) : fieldViews;
  }, [fieldViews, search]);
  const tableRules = useMemo(() => draftRules.filter((rule) =>
    rule.sliceId === selectedApplication?.id && normalize(rule.table) === normalize(effectiveTable)
  ), [draftRules, effectiveTable, selectedApplication?.id]);
  const approvedCount = tableRules.filter((rule) => normalize(rule.piiStatus) === 'approved' && rule.piiType).length;
  const linkedCount = fieldViews.filter((field) => field.rule.dependencyGroup).length;

  const analysisMutation = useMutation({
    mutationFn: async (): Promise<AnalysisResult> => {
      if (!selectedApplication || !effectiveTable) throw new Error('Choose an application and table first.');
      const [findings, profile] = await Promise.all([
        apiPost<DiscoveryFinding[]>(
          `/api/discovery/scan/${selectedApplication.dataSourceId}?schema=${encodeURIComponent(selectedApplication.schema)}`,
          { piiTypes: [], tableNames: [effectiveTable] }
        ),
        apiPost<ProfileResponse>('/api/synthetic/profile', {
          dataSourceId: selectedApplication.dataSourceId,
          schema: selectedApplication.schema,
          table: effectiveTable,
          bankingSafeProfile: true
        })
      ]);
      return { findings, profile };
    },
    onSuccess: ({ findings, profile }) => {
      if (!selectedApplication) return;
      setDraftRules((current) => mergeAnalysis(
        current,
        selectedApplication,
        effectiveTable,
        columnQuery.data || [],
        findings,
        profile,
        crossLinks
      ));
      void queryClient.invalidateQueries({
        queryKey: keys.discovery.tableColumns(selectedApplication.dataSourceId, selectedApplication.schema, effectiveTable, '')
      });
      notifications.show({
        color: 'green',
        title: 'Field analysis complete',
        message: `${findings.length} PII signal${findings.length === 1 ? '' : 's'} and ${profile.columns?.length || 0} generation recommendations were evaluated from live source samples.`
      });
    },
    onError: (error) => notifications.show({
      color: 'red',
      title: 'Could not analyze table',
      message: error instanceof Error ? error.message : 'Unexpected analysis error'
    })
  });

  const previewMutation = useMutation({
    mutationFn: async ({ field, value }: { field: FieldView; value: string }) => {
      if (!field.rule.maskFunction) throw new Error('Choose a masking function first.');
      if (!value.trim()) throw new Error('Enter a sample value to preview.');
      return apiPost<MaskPreview>('/api/policies/preview', {
        function: field.rule.maskFunction,
        value,
        param1: field.rule.maskParam1 || '',
        param2: field.rule.maskParam2 || '',
        salt: field.rule.dependencyGroup || `${selectedApplication?.dataSourceName}.${selectedApplication?.schema}.${effectiveTable}.${field.column}`
      });
    },
    onSuccess: (result, variables) => setPreviewResults((current) => ({ ...current, [normalize(variables.field.column)]: result })),
    onError: (error) => notifications.show({
      color: 'red',
      title: 'Mask preview failed',
      message: error instanceof Error ? error.message : 'Unexpected preview error'
    })
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!selectedApplication) throw new Error('Choose an application first.');
      const unsafeLinkedRule = tableRules.find((rule) => rule.dependencyGroup
        && rule.maskFunction
        && !DETERMINISTIC_LINK_FUNCTIONS.has(rule.maskFunction));
      if (unsafeLinkedRule) {
        throw new Error(`${unsafeLinkedRule.column} is linked to another application. Choose a deterministic masking function before saving.`);
      }
      const persisted: PersistedFinding[] = [];
      for (const rule of tableRules) {
        if (!rule.piiType || !rule.piiStatus) continue;
        const body = {
          status: rule.piiStatus.toUpperCase(),
          suggestedFunction: rule.maskFunction || defaultFunctionForPii(rule.piiType),
          suggestedParam1: rule.maskParam1 || '',
          suggestedParam2: rule.maskParam2 || ''
        };
        if (rule.classificationId) {
          persisted.push(await apiPatch<DiscoveryFinding>(`/api/discovery/classifications/${rule.classificationId}`, body));
        } else {
          persisted.push(await apiPost<DiscoveryFinding>(`/api/discovery/manual/${selectedApplication.dataSourceId}`, {
            schemaName: selectedApplication.schema,
            tableName: effectiveTable,
            columnName: rule.column,
            piiType: rule.piiType,
            ...body
          }));
        }
      }
      return persisted;
    },
    onSuccess: (persisted) => {
      const saved = mergePersistedIds(draftRules, selectedApplication?.id || '', effectiveTable, persisted);
      setDraftRules(saved);
      onSave(saved);
      notifications.show({
        color: 'green',
        title: 'Field intelligence saved',
        message: 'PII decisions, masking rules, generator mappings, and dependency groups are now part of this architecture.'
      });
    },
    onError: (error) => notifications.show({
      color: 'red',
      title: 'Could not save field rules',
      message: error instanceof Error ? error.message : 'Unexpected save error'
    })
  });

  const updateRule = (field: FieldView, patch: Partial<ArchitectureFieldRule>, propagateMask = false) => {
    if (!selectedApplication || readOnly) return;
    if (propagateMask && patch.maskFunction && !DETERMINISTIC_LINK_FUNCTIONS.has(patch.maskFunction)) {
      notifications.show({
        color: 'yellow',
        title: 'Choose a relationship-safe mask',
        message: 'Connected columns must use a deterministic function so the same business key remains aligned in every application.'
      });
      return;
    }
    setDraftRules((current) => {
      const endpoint = endpointKey(selectedApplication.id, effectiveTable, field.column);
      const component = propagateMask ? connectedFieldKeys(endpoint, crossLinks) : new Set([endpoint]);
      let next = current;
      component.forEach((key) => {
        const target = decodeEndpoint(key);
        if (!target) return;
        const relationship = dependencyFor(target.sliceId, target.table, target.column, crossLinks);
        next = upsertRule(next, {
          id: ruleId(target.sliceId, target.table, target.column),
          sliceId: target.sliceId,
          table: target.table,
          column: target.column,
          ...relationship,
          ...(key === endpoint ? patch : maskPatch(patch)),
          updatedAt: new Date().toISOString()
        });
      });
      return next;
    });
  };

  const switchApplication = (sliceId: string | null) => {
    const application = applications.find((candidate) => candidate.id === sliceId);
    setSelectedSliceId(sliceId || '');
    setSelectedTable(application?.tables[0] || '');
    setExpandedField(null);
    setSearch('');
  };

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      fullScreen
      title={null}
      padding={0}
      classNames={{ content: 'entity-field-workspace-modal', body: 'entity-field-workspace-modal-body' }}
    >
      <div className="entity-field-workspace">
        <header className="entity-field-workspace-head">
          <div>
            <Group gap="xs">
              <Text fw={850} size="lg">Field intelligence</Text>
              <Badge variant="light" color={readOnly ? 'green' : 'blue'}>{readOnly ? 'READ ONLY' : 'EDITABLE'}</Badge>
            </Group>
            <Text size="sm" c="dimmed">Profile real values, govern PII, and keep masking and generation consistent across every connected application.</Text>
          </div>
          <Group gap="sm">
            {!readOnly ? <Button
              variant="light"
              leftSection={<IconSparkles size={16} />}
              loading={analysisMutation.isPending}
              disabled={!selectedApplication || !effectiveTable}
              onClick={() => analysisMutation.mutate()}
            >Analyze live samples</Button> : null}
            {!readOnly ? <Button
              leftSection={<IconShieldCheck size={16} />}
              loading={saveMutation.isPending}
              disabled={!selectedApplication || !effectiveTable}
              onClick={() => saveMutation.mutate()}
            >Save rules</Button> : null}
            <Button variant="default" onClick={onClose}>Close</Button>
          </Group>
        </header>

        <section className="entity-field-workspace-context">
          <Select
            label="Application"
            data={applications.map((application) => ({ value: application.id, label: `${application.label} · ${application.dataSourceName}` }))}
            value={selectedApplication?.id || null}
            onChange={switchApplication}
            searchable
            allowDeselect={false}
          />
          <Select
            label="Table"
            data={(selectedApplication?.tables || []).map((table) => ({ value: table, label: table }))}
            value={effectiveTable || null}
            onChange={(value) => {
              setSelectedTable(value || '');
              setExpandedField(null);
              setSearch('');
            }}
            searchable
            allowDeselect={false}
          />
          <TextInput label="Find field" placeholder="Column, PII type, mask, or generator" value={search} onChange={(event) => setSearch(event.currentTarget.value)} />
          <div className="entity-field-workspace-metrics">
            <div><small>FIELDS</small><b>{fieldViews.length}</b></div>
            <div><small>APPROVED PII</small><b>{approvedCount}</b></div>
            <div><small>LINKED FIELDS</small><b>{linkedCount}</b></div>
          </div>
        </section>

        <section className="entity-field-workspace-content">
          {columnQuery.isPending || reviewQuery.isPending ? (
            <div className="entity-field-workspace-state"><Loader size="sm" /><Text size="sm">Loading field metadata...</Text></div>
          ) : columnQuery.error ? (
            <div className="entity-field-workspace-state is-error"><Text fw={700}>Columns could not be loaded</Text><Text size="sm">{columnQuery.error instanceof Error ? columnQuery.error.message : 'Unexpected metadata error'}</Text></div>
          ) : !visibleFields.length ? (
            <div className="entity-field-workspace-state"><IconDatabaseSearch size={30} /><Text fw={700}>No matching fields</Text><Text c="dimmed" size="sm">Clear the search or choose a table with columns.</Text></div>
          ) : (
            <div className="entity-field-list">
              {visibleFields.map((field) => {
                const key = normalize(field.column);
                const expanded = expandedField === key;
                const approved = normalize(field.rule.piiStatus) === 'approved';
                const rejected = normalize(field.rule.piiStatus) === 'rejected';
                const linkedEndpoints = field.rule.dependencyGroup ? connectedFieldKeys(
                  endpointKey(field.rule.sliceId, field.rule.table, field.rule.column),
                  crossLinks
                ).size : 1;
                const compatibleMaskOptions = compatibleFunctions(maskFunctionsQuery.data || [], field.dataType, field.rule.maskFunction);
                const maskOptions = field.rule.dependencyGroup
                  ? compatibleMaskOptions.filter((option) => DETERMINISTIC_LINK_FUNCTIONS.has(option.value))
                  : compatibleMaskOptions;
                const maskParams = paramLabels(field.rule.maskFunction);
                const sample = previewValues[key] ?? field.review?.sampleValue ?? '';
                const preview = previewResults[key];
                return (
                  <article className={`entity-field-row${expanded ? ' is-expanded' : ''}`} key={field.column}>
                    <div className="entity-field-row-summary">
                      <button className="entity-field-expand" type="button" onClick={() => setExpandedField(expanded ? null : key)} aria-label={`${expanded ? 'Collapse' : 'Configure'} ${field.column}`}>
                        {expanded ? <IconChevronDown size={17} /> : <IconChevronRight size={17} />}
                      </button>
                      <div className="entity-field-identity">
                        <b>{field.column}</b>
                        <small>{field.dataType}{field.nullable === false ? ' · required' : ''}</small>
                      </div>
                      <div className="entity-field-classification">
                        {field.rule.piiType ? <Badge variant="light" color={approved ? 'green' : rejected ? 'gray' : 'yellow'}>{field.rule.piiType}</Badge> : <Badge variant="light" color="gray">NOT CLASSIFIED</Badge>}
                        {field.rule.confidence ? <span>{Math.round(field.rule.confidence * (field.rule.confidence <= 1 ? 100 : 1))}% evidence</span> : null}
                      </div>
                      <div className="entity-field-rule-summary">
                        <span><b>{field.rule.maskFunction || 'No mask'}</b><small>Masking</small></span>
                        <span><b>{field.rule.generator || 'Not mapped'}</b><small>Generation</small></span>
                      </div>
                      <div className="entity-field-dependency">
                        {field.rule.dependencyGroup ? <Badge leftSection={<IconGitBranch size={11} />} variant="light" color="violet">{field.rule.dependencyRole || 'LINKED'} · {linkedEndpoints} fields</Badge> : <Text size="xs" c="dimmed">Independent</Text>}
                      </div>
                      <Group gap={4} wrap="nowrap">
                        <Tooltip label="Approve as PII"><ActionIcon
                          color="green"
                          variant={approved ? 'filled' : 'light'}
                          disabled={readOnly}
                          aria-label={`Approve ${field.column} as PII`}
                          onClick={() => updateRule(field, {
                            piiStatus: 'APPROVED',
                            piiType: field.rule.piiType || field.review?.piiType || 'MANUAL_PII',
                            maskFunction: field.rule.maskFunction || (field.rule.dependencyGroup
                              ? 'FORMAT_PRESERVE'
                              : defaultFunctionForPii(field.rule.piiType || field.review?.piiType))
                          })}
                        ><IconCheck size={15} /></ActionIcon></Tooltip>
                        <Tooltip label="Mark not PII"><ActionIcon
                          color="red"
                          variant={rejected ? 'filled' : 'light'}
                          disabled={readOnly}
                          aria-label={`Reject ${field.column} as PII`}
                          onClick={() => updateRule(field, { piiStatus: 'REJECTED' })}
                        ><IconX size={15} /></ActionIcon></Tooltip>
                      </Group>
                    </div>

                    {expanded ? <div className="entity-field-row-details">
                      <section>
                        <Group justify="space-between" mb="xs"><div><Text fw={750} size="sm">PII and masking</Text><Text size="xs" c="dimmed">Linked columns receive the same deterministic mask and semantic dependency group.</Text></div>{field.rule.recommendationSource ? <Badge variant="outline">{field.rule.recommendationSource}</Badge> : null}</Group>
                        <div className="entity-field-control-grid">
                          <Select
                            label="PII type"
                            data={piiTypeOptions}
                            value={field.rule.piiType || null}
                            disabled={readOnly}
                            searchable
                            clearable
                            onChange={(value) => updateRule(field, {
                              piiType: value,
                              piiStatus: value ? field.rule.piiStatus || 'SUGGESTED' : null,
                              maskFunction: value ? field.rule.maskFunction || (field.rule.dependencyGroup ? 'FORMAT_PRESERVE' : defaultFunctionForPii(value)) : null,
                              recommendationSource: value ? field.rule.recommendationSource || 'MANUAL' : null
                            })}
                          />
                          <Select
                            label="Masking function"
                            data={maskOptions}
                            value={field.rule.maskFunction || null}
                            disabled={readOnly || !field.rule.piiType}
                            searchable
                            clearable
                            onChange={(value) => updateRule(field, {
                              maskFunction: value,
                              recommendationSource: value ? 'MANUAL' : field.rule.recommendationSource
                            }, Boolean(field.rule.dependencyGroup))}
                          />
                          {[0, 1].map((index) => {
                            const meta = maskParams[index];
                            const property = index === 0 ? 'maskParam1' : 'maskParam2';
                            return <TextInput
                              key={property}
                              label={meta?.label || `Mask parameter ${index + 1}`}
                              placeholder={meta ? 'Optional' : 'Not used by this function'}
                              value={String(field.rule[property] || '')}
                              disabled={readOnly || !field.rule.maskFunction || !meta}
                              onChange={(event) => updateRule(field, { [property]: event.currentTarget.value }, Boolean(field.rule.dependencyGroup))}
                            />;
                          })}
                        </div>
                        {field.rule.dependencyGroup && field.rule.maskFunction && !DETERMINISTIC_LINK_FUNCTIONS.has(field.rule.maskFunction) ? <Text className="entity-field-warning" size="xs">This linked field uses a function that may not produce an identical token in every system. Prefer FORMAT_PRESERVE, CHARACTER_MAP, HASH_LOOKUP, or DIRECT_LOOKUP for cross-application identity keys.</Text> : null}
                        <Divider my="sm" />
                        <div className="entity-field-preview">
                          <TextInput label="Safe preview value" value={sample} onChange={(event) => setPreviewValues((current) => ({ ...current, [key]: event.currentTarget.value }))} />
                          <Button variant="light" leftSection={<IconEye size={15} />} loading={previewMutation.isPending && previewMutation.variables?.field.column === field.column} disabled={!field.rule.maskFunction || !sample.trim()} onClick={() => previewMutation.mutate({ field, value: sample })}>Preview</Button>
                          <div><small>MASKED RESULT</small><b>{preview?.masked ?? 'Run preview'}</b></div>
                        </div>
                      </section>

                      <section>
                        <Group justify="space-between" mb="xs"><div><Text fw={750} size="sm">Synthetic generation</Text><Text size="xs" c="dimmed">Live profiling recommends data shape; relationship keys use parent lookup to preserve RI.</Text></div>{field.rule.confidence ? <Badge color={confidencePercent(field.rule.confidence) >= 90 ? 'green' : 'yellow'} variant="light">{confidencePercent(field.rule.confidence)}% confidence</Badge> : null}</Group>
                        <div className="entity-field-control-grid is-generation">
                          <Select
                            label="Generator"
                            data={generatorOptions}
                            value={field.rule.generator || null}
                            disabled={readOnly}
                            searchable
                            clearable
                            onChange={(value) => updateRule(field, {
                              generator: value,
                              recommendationSource: value ? 'MANUAL' : field.rule.recommendationSource,
                              confidence: value ? 1 : field.rule.confidence
                            })}
                          />
                          <TextInput label="Generator parameter 1" value={String(field.rule.generatorParam1 || '')} disabled={readOnly || !field.rule.generator} onChange={(event) => updateRule(field, { generatorParam1: event.currentTarget.value })} />
                          <TextInput label="Generator parameter 2" value={String(field.rule.generatorParam2 || '')} disabled={readOnly || !field.rule.generator} onChange={(event) => updateRule(field, { generatorParam2: event.currentTarget.value })} />
                        </div>
                        {field.rule.dependencyRole === 'CHILD' ? <div className="entity-field-ri-note"><IconGitBranch size={16} /><div><b>Relationship-safe lookup</b><small>This field draws values from the related parent column instead of generating an independent key.</small></div></div> : null}
                        {field.rule.dependencyRole === 'PARENT' ? <div className="entity-field-ri-note"><IconGitBranch size={16} /><div><b>Parent key source</b><small>Dependent fields use this relationship group when their rows are generated.</small></div></div> : null}
                      </section>
                    </div> : null}
                  </article>
                );
              })}
            </div>
          )}
        </section>

        <footer className="entity-field-workspace-footer">
          <Text size="xs" c="dimmed">Recommendations are evidence-based, not blind guesses: discovery inspects sampled values, profiling evaluates data distributions, and relationship mappings override child-key generation to preserve RI.</Text>
          <Group gap="sm">
            <Button variant="default" leftSection={<IconRefresh size={15} />} onClick={() => {
              setDraftRules(rules);
              setPreviewResults({});
            }} disabled={readOnly}>Discard unsaved</Button>
            {!readOnly ? <Button loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>Save field intelligence</Button> : null}
          </Group>
        </footer>
      </div>
    </Modal>
  );
}

function buildFieldViews(
  application: LoadedApplication | null,
  table: string,
  columns: DataColumn[],
  reviews: DiscoveryColumnReviewRow[],
  rules: ArchitectureFieldRule[],
  links: CrossDatabaseLink[]
): FieldView[] {
  if (!application || !table) return [];
  const reviewByColumn = new Map(reviews.map((review) => [normalize(review.columnName), review]));
  const columnByName = new Map(columns.map((column) => [normalize(column.column), column]));
  reviews.forEach((review) => {
    if (!columnByName.has(normalize(review.columnName))) {
      columnByName.set(normalize(review.columnName), {
        column: review.columnName,
        type: review.dataType || '',
        nullable: review.nullable
      });
    }
  });
  return [...columnByName.values()].sort((a, b) => a.column.localeCompare(b.column)).map((column) => {
    const review = reviewByColumn.get(normalize(column.column));
    const existing = rules.find((rule) => rule.sliceId === application.id
      && normalize(rule.table) === normalize(table)
      && normalize(rule.column) === normalize(column.column));
    const relationship = dependencyFor(application.id, table, column.column, links);
    const discoveredMask = review?.suggestedFunction || (review?.piiType ? defaultFunctionForPii(review.piiType) : null);
    const baseRule: ArchitectureFieldRule = existing ? {
      ...existing,
      ...relationship
    } : {
      id: ruleId(application.id, table, column.column),
      sliceId: application.id,
      table,
      column: column.column,
      dataType: String(column.type || review?.dataType || ''),
      classificationId: review?.classificationId,
      piiType: review?.piiType,
      piiStatus: review?.status || (review?.piiType ? 'SUGGESTED' : null),
      maskFunction: relationship.dependencyGroup ? relationshipSafeMask(discoveredMask) : discoveredMask,
      maskParam1: review?.suggestedParam1,
      maskParam2: review?.suggestedParam2,
      confidence: review?.confidence,
      recommendationSource: review?.piiType ? 'DISCOVERY' : null,
      ...relationship
    };
    return {
      column: column.column,
      dataType: String(column.type || review?.dataType || ''),
      nullable: column.nullable ?? review?.nullable,
      review,
      rule: baseRule
    };
  });
}

function mergeAnalysis(
  current: ArchitectureFieldRule[],
  application: LoadedApplication,
  table: string,
  columns: DataColumn[],
  findings: DiscoveryFinding[],
  profile: ProfileResponse,
  links: CrossDatabaseLink[]
) {
  let next = [...current];
  const findingByColumn = new Map(findings
    .filter((finding) => normalize(finding.tableName) === normalize(table))
    .map((finding) => [normalize(finding.columnName), finding]));
  const profileByColumn = new Map((profile.columns || []).map((column) => [normalize(column.name), column]));
  const allColumns = new Map(columns.map((column) => [normalize(column.column), column]));
  findings.forEach((finding) => {
    if (!allColumns.has(normalize(finding.columnName))) allColumns.set(normalize(finding.columnName), { column: finding.columnName, type: finding.dataType });
  });
  (profile.columns || []).forEach((column) => {
    if (column.name && !allColumns.has(normalize(column.name))) allColumns.set(normalize(column.name), { column: column.name, type: column.sqlType });
  });

  allColumns.forEach((column) => {
    const finding = findingByColumn.get(normalize(column.column));
    const recommendation = profileByColumn.get(normalize(column.column));
    const relationship = dependencyFor(application.id, table, column.column, links);
    const isChild = relationship.dependencyRole === 'CHILD';
    const existing = next.find((rule) => rule.sliceId === application.id
      && normalize(rule.table) === normalize(table)
      && normalize(rule.column) === normalize(column.column));
    const discoveredMask = existing?.maskFunction || finding?.suggestedFunction
      || (finding?.piiType ? defaultFunctionForPii(finding.piiType) : null);
    next = upsertRule(next, {
      id: ruleId(application.id, table, column.column),
      sliceId: application.id,
      table,
      column: column.column,
      dataType: String(column.type || finding?.dataType || recommendation?.sqlType || ''),
      classificationId: finding?.id || existing?.classificationId,
      piiType: finding?.piiType || existing?.piiType,
      piiStatus: finding?.status || existing?.piiStatus || (finding ? 'SUGGESTED' : null),
      maskFunction: relationship.dependencyGroup ? relationshipSafeMask(discoveredMask) : discoveredMask,
      maskParam1: existing?.maskParam1 || finding?.suggestedParam1,
      maskParam2: existing?.maskParam2 || finding?.suggestedParam2,
      generator: isChild ? 'LOOKUP' : existing?.generator || recommendation?.generator,
      generatorParam1: isChild ? relationship.parentColumn || recommendation?.param1 : existing?.generatorParam1 || recommendation?.param1,
      generatorParam2: isChild ? column.column : existing?.generatorParam2 || recommendation?.param2,
      confidence: relationship.dependencyGroup ? 1 : finding?.confidence || (recommendation?.generator ? 0.95 : existing?.confidence),
      recommendationSource: relationship.dependencyGroup ? 'RELATIONSHIP' : finding?.piiType ? 'DISCOVERY' : recommendation?.generator ? 'PROFILE' : existing?.recommendationSource,
      ...relationship,
      updatedAt: new Date().toISOString()
    });
  });
  return next;
}

function dependencyFor(sliceId: string, table: string, column: string, links: CrossDatabaseLink[]) {
  const child = links.find((link) => link.childSliceId === sliceId
    && normalize(link.childTable) === normalize(table)
    && normalize(link.childColumn) === normalize(column));
  const parent = links.find((link) => link.parentSliceId === sliceId
    && normalize(link.parentTable) === normalize(table)
    && normalize(link.parentColumn) === normalize(column));
  if (child && parent) return { dependencyGroup: child.id, dependencyRole: 'PEER' as const, parentColumn: child.parentColumn };
  if (child) return { dependencyGroup: child.id, dependencyRole: 'CHILD' as const, parentColumn: child.parentColumn };
  if (parent) return { dependencyGroup: parent.id, dependencyRole: 'PARENT' as const, parentColumn: parent.parentColumn };
  return { dependencyGroup: null, dependencyRole: null, parentColumn: null };
}

function connectedFieldKeys(start: string, links: CrossDatabaseLink[]) {
  const seen = new Set([start]);
  const queue = [start];
  while (queue.length) {
    const current = queue.shift() as string;
    links.forEach((link) => {
      const parent = endpointKey(link.parentSliceId, link.parentTable, link.parentColumn);
      const child = endpointKey(link.childSliceId, link.childTable, link.childColumn);
      const next = parent === current ? child : child === current ? parent : null;
      if (next && !seen.has(next)) {
        seen.add(next);
        queue.push(next);
      }
    });
  }
  return seen;
}

function upsertRule(rules: ArchitectureFieldRule[], patch: ArchitectureFieldRule) {
  const index = rules.findIndex((rule) => rule.sliceId === patch.sliceId
    && normalize(rule.table) === normalize(patch.table)
    && normalize(rule.column) === normalize(patch.column));
  if (index < 0) return [...rules, patch];
  const next = [...rules];
  next[index] = { ...next[index], ...patch };
  return next;
}

function maskPatch(patch: Partial<ArchitectureFieldRule>): Partial<ArchitectureFieldRule> {
  const propagated: Partial<ArchitectureFieldRule> = {};
  if ('maskFunction' in patch) propagated.maskFunction = patch.maskFunction;
  if ('maskParam1' in patch) propagated.maskParam1 = patch.maskParam1;
  if ('maskParam2' in patch) propagated.maskParam2 = patch.maskParam2;
  if ('recommendationSource' in patch) propagated.recommendationSource = patch.recommendationSource;
  return propagated;
}

function relationshipSafeMask(maskFunction?: string | null) {
  return maskFunction && DETERMINISTIC_LINK_FUNCTIONS.has(maskFunction) ? maskFunction : 'FORMAT_PRESERVE';
}

function mergePersistedIds(rules: ArchitectureFieldRule[], sliceId: string, table: string, findings: PersistedFinding[]) {
  const persistedByColumn = new Map(findings.map((finding) => [normalize(finding.columnName), finding]));
  return rules.map((rule) => {
    if (rule.sliceId !== sliceId || normalize(rule.table) !== normalize(table)) return rule;
    const persisted = persistedByColumn.get(normalize(rule.column));
    return persisted?.id ? { ...rule, classificationId: persisted.id } : rule;
  });
}

function generatorCatalogOptions(generators: GeneratorSpec[]) {
  const options = generators.flatMap((generator) => {
    const value = String(generator.name || generator.id || '').trim();
    return value ? [{ value, label: generator.label ? `${generator.label} (${value})` : value }] : [];
  });
  if (!options.some((option) => option.value === 'LOOKUP')) options.unshift({ value: 'LOOKUP', label: 'Relationship lookup (LOOKUP)' });
  return options.sort((a, b) => a.label.localeCompare(b.label));
}

function ruleId(sliceId: string, table: string, column: string) {
  return `${sliceId}:${normalize(table)}:${normalize(column)}`;
}

function endpointKey(sliceId: string, table: string, column: string) {
  return `${encodeURIComponent(sliceId)}|${encodeURIComponent(table)}|${encodeURIComponent(column)}`;
}

function decodeEndpoint(value: string) {
  const parts = value.split('|');
  if (parts.length !== 3) return null;
  try {
    return { sliceId: decodeURIComponent(parts[0]), table: decodeURIComponent(parts[1]), column: decodeURIComponent(parts[2]) };
  } catch {
    return null;
  }
}

function confidencePercent(value?: number | null) {
  if (!value) return 0;
  return Math.round(value <= 1 ? value * 100 : value);
}

function normalize(value?: string | null) {
  return String(value || '').trim().toLowerCase();
}
