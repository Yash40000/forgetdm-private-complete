'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Group, Modal, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { useDisclosure, useLocalStorage } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconEdit, IconGitMerge, IconLink, IconLock, IconPlus, IconShieldSearch, IconTablePlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';

import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DiscoveryGraph, DiscoveryGraphEdge } from '@/features/pii-discovery/types';
import { ApplicationDrawer } from './application-drawer';
import { ArchitectureCanvas } from './architecture-canvas';
import { CrossLinkDrawer } from './cross-link-drawer';
import { FieldIntelligenceWorkspace } from './field-intelligence-workspace';
import { MergeDrawer, type EntityMergeDraft } from './merge-drawer';
import type {
  ApplicationSlice,
  ArchitectureFieldRule,
  ArchitectureModelStatus,
  BusinessEntityMemberRequest,
  CreatedArchitecture,
  CrossDatabaseLink,
  IdentityAnchor,
  LoadedApplication
} from './types';

const STORAGE_KEY = 'forgetdm.entity-architecture.applications';
const CROSS_LINK_STORAGE_KEY = 'forgetdm.entity-architecture.cross-links';
const FIELD_RULE_STORAGE_KEY = 'forgetdm.entity-architecture.field-rules';
const MODEL_STATUS_STORAGE_KEY = 'forgetdm.entity-architecture.model-status';
const MODEL_NAME_STORAGE_KEY = 'forgetdm.entity-architecture.model-name';

export function EntityArchitecturePage() {
  const queryClient = useQueryClient();
  const [applications, setApplications] = useLocalStorage<ApplicationSlice[]>({ key: STORAGE_KEY, defaultValue: [] });
  const [crossLinks, setCrossLinks] = useLocalStorage<CrossDatabaseLink[]>({ key: CROSS_LINK_STORAGE_KEY, defaultValue: [] });
  const [fieldRules, setFieldRules] = useLocalStorage<ArchitectureFieldRule[]>({ key: FIELD_RULE_STORAGE_KEY, defaultValue: [] });
  const [modelStatus, setModelStatus] = useLocalStorage<ArchitectureModelStatus>({ key: MODEL_STATUS_STORAGE_KEY, defaultValue: 'DRAFT' });
  const [modelName, setModelName] = useLocalStorage<string>({ key: MODEL_NAME_STORAGE_KEY, defaultValue: '' });
  const [anchors, setAnchors] = useState<IdentityAnchor[]>([]);
  const [entity, setEntity] = useState<CreatedArchitecture['entity'] | null>(null);
  const [editing, setEditing] = useState<ApplicationSlice | null>(null);
  const [focusedFieldTable, setFocusedFieldTable] = useState<{ sliceId: string; table: string } | null>(null);
  const [modelNameDraft, setModelNameDraft] = useState('');
  const [applicationOpened, applicationDrawer] = useDisclosure(false);
  const [crossLinkOpened, crossLinkDrawer] = useDisclosure(false);
  const [mergeOpened, mergeDrawer] = useDisclosure(false);
  const [fieldWorkspaceOpened, fieldWorkspace] = useDisclosure(false);
  const [createModelOpened, createModel] = useDisclosure(false);
  const readOnly = modelStatus === 'CREATED';
  const totalTables = applications.reduce((count, application) => count + application.tables.length, 0);

  const graphQueries = useQueries({
    queries: applications.map((application) => ({
      queryKey: keys.discovery.graph(application.dataSourceId, application.schema, ''),
      queryFn: () => apiFetch<DiscoveryGraph>(
        `/api/discovery/graph/${application.dataSourceId}?schema=${encodeURIComponent(application.schema)}`
      ),
      staleTime: 30_000
    }))
  });
  const graphVersion = graphQueries
    .map((query) => `${query.dataUpdatedAt}:${query.errorUpdatedAt}:${query.fetchStatus}`)
    .join('|');
  const loadedApplications = useMemo<LoadedApplication[]>(
    () => applications.map((application, index) => ({
      ...application,
      graph: graphQueries[index]?.data,
      graphLoading: graphQueries[index]?.isPending,
      graphError: graphQueries[index]?.error instanceof Error ? graphQueries[index].error.message : null
    })),
    [applications, graphVersion]
  );

  const createEntity = useMutation({
    mutationFn: (draft: EntityMergeDraft) => {
      const primary = loadedApplications.find((application) => application.id === draft.primarySliceId);
      const primaryAnchor = draft.anchors.find((anchor) => anchor.sliceId === draft.primarySliceId);
      if (!primary || !primaryAnchor) throw new Error('Primary application identity is incomplete');
      return apiPost<CreatedArchitecture>('/api/business-entities/architecture', {
        name: draft.name,
        description: draft.description || null,
        domain: draft.domain || null,
        rootTable: primaryAnchor.table,
        businessKeyColumns: primaryAnchor.column,
        members: buildMembers(loadedApplications, draft, crossLinks, fieldRules)
      });
    },
    onSuccess: (created, draft) => {
      setAnchors(draft.anchors);
      setEntity(created.entity || null);
      mergeDrawer.close();
      queryClient.invalidateQueries({ queryKey: keys.businessEntity.all });
      notifications.show({ color: 'green', title: 'Business entity created', message: `${draft.name} now links ${applications.length} applications.` });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create entity', message: error instanceof Error ? error.message : 'Unexpected error' })
  });

  const saveApplication = (slice: ApplicationSlice) => {
    if (readOnly) return;
    setApplications((current) => {
      const found = current.some((item) => item.id === slice.id);
      return found ? current.map((item) => item.id === slice.id ? slice : item) : [...current, slice];
    });
    setAnchors((current) => current.filter((anchor) => anchor.sliceId !== slice.id));
    setCrossLinks((current) => current.filter((link) => {
      if (link.parentSliceId === slice.id && !slice.tables.some((table) => normalize(table) === normalize(link.parentTable))) return false;
      if (link.childSliceId === slice.id && !slice.tables.some((table) => normalize(table) === normalize(link.childTable))) return false;
      return true;
    }));
    setFieldRules((current) => current.filter((rule) =>
      rule.sliceId !== slice.id
      || slice.tables.some((table) => normalize(table) === normalize(rule.table))
    ));
    setEntity(null);
    applicationDrawer.close();
  };

  const removeApplication = (sliceId: string) => {
    if (readOnly) return;
    setApplications((current) => current.filter((item) => item.id !== sliceId));
    setAnchors((current) => current.filter((anchor) => anchor.sliceId !== sliceId));
    setCrossLinks((current) => current.filter((link) => link.parentSliceId !== sliceId && link.childSliceId !== sliceId));
    setFieldRules((current) => current.filter((rule) => rule.sliceId !== sliceId));
    setEntity(null);
  };

  const removeTable = (sliceId: string, tableName: string) => {
    if (readOnly) return;
    setApplications((current) => current.flatMap((application) => {
      if (application.id !== sliceId) return [application];
      const tables = application.tables.filter((table) => normalize(table) !== normalize(tableName));
      return tables.length ? [{ ...application, tables }] : [];
    }));
    setAnchors((current) => current.filter((anchor) =>
      anchor.sliceId !== sliceId || normalize(anchor.table) !== normalize(tableName)
    ));
    setCrossLinks((current) => current.filter((link) =>
      !(link.parentSliceId === sliceId && normalize(link.parentTable) === normalize(tableName))
      && !(link.childSliceId === sliceId && normalize(link.childTable) === normalize(tableName))
    ));
    setFieldRules((current) => current.filter((rule) =>
      rule.sliceId !== sliceId || normalize(rule.table) !== normalize(tableName)
    ));
    setEntity(null);
    notifications.show({
      color: 'blue',
      title: 'Table removed from architecture',
      message: `${tableName} was removed from this canvas. The physical database table was not changed.`
    });
  };

  return (
    <main className="entity-architecture-page">
      <header className="entity-architecture-header">
        <div>
          <Text className="entity-architecture-eyebrow">DATA FOUNDATION</Text>
          <h1>Entity Architecture</h1>
          <Text c="dimmed">Compose selected tables from multiple applications into one governed business entity.</Text>
        </div>
        <Group gap="sm">
          {applications.length ? <Badge color={readOnly ? 'green' : 'blue'} variant="light" leftSection={readOnly ? <IconLock size={12} /> : undefined}>{readOnly ? `${modelName || 'Entity application'} / published` : 'Draft architecture'}</Badge> : null}
          {entity?.id ? (
            <Button component={Link} href="/business-entities" variant="subtle" size="sm">
              Open {entity.name || 'business entity'}
            </Button>
          ) : null}
          {!readOnly ? <Button
            variant="default"
            leftSection={<IconPlus size={17} />}
            onClick={() => {
              setEditing(null);
              applicationDrawer.open();
            }}
          >
            Add source application
          </Button> : null}
          <Button
            variant="default"
            leftSection={<IconLink size={17} />}
            disabled={readOnly || totalTables < 2}
            onClick={crossLinkDrawer.open}
          >
            Manage links{crossLinks.length ? ` (${crossLinks.length})` : ''}
          </Button>
          <Button
            variant="default"
            leftSection={<IconShieldSearch size={17} />}
            disabled={!totalTables}
            onClick={() => {
              setFocusedFieldTable(null);
              fieldWorkspace.open();
            }}
          >
            Field intelligence
          </Button>
          <Button
            leftSection={<IconGitMerge size={17} />}
            disabled={readOnly || applications.length < 2}
            onClick={mergeDrawer.open}
          >
            Merge as entity
          </Button>
          {readOnly ? (
            <Button leftSection={<IconEdit size={17} />} onClick={() => setModelStatus('DRAFT')}>Edit entity application</Button>
          ) : (
            <Button
              color="teal"
              leftSection={<IconLock size={16} />}
              disabled={!totalTables}
              onClick={() => {
                setModelNameDraft(modelName || applications[0]?.label || '');
                createModel.open();
              }}
            >
              Publish entity application
            </Button>
          )}
        </Group>
      </header>

      {applications.length ? (
        <section className="entity-architecture-app-strip" aria-label="Applications on canvas">
          {applications.map((application) => (
            <div className="entity-architecture-app-item" key={application.id}>
              <div>
                <Group gap="xs" wrap="nowrap">
                  <Text fw={700} size="sm">{application.label}</Text>
                  <Badge variant="light" size="xs">{application.tables.length} tables</Badge>
                </Group>
                <Text c="dimmed" size="xs">{application.dataSourceName} · {application.schema}</Text>
              </div>
              <Group gap={2} wrap="nowrap">
                {!readOnly ? <Tooltip label="Add or remove tables">
                  <ActionIcon
                    variant="subtle"
                    aria-label={`Add tables to ${application.label}`}
                    onClick={() => {
                      setEditing(application);
                      applicationDrawer.open();
                    }}
                  >
                    <IconTablePlus size={16} />
                  </ActionIcon>
                </Tooltip> : null}
                {!readOnly ? <Tooltip label="Remove from canvas">
                  <ActionIcon color="red" variant="subtle" aria-label={`Remove ${application.label}`} onClick={() => removeApplication(application.id)}>
                    <IconTrash size={16} />
                  </ActionIcon>
                </Tooltip> : null}
              </Group>
            </div>
          ))}
        </section>
      ) : null}

      <ArchitectureCanvas
        applications={loadedApplications}
        anchors={anchors}
        crossLinks={crossLinks}
        entityName={entity?.name}
        fieldRules={fieldRules}
        readOnly={readOnly}
        onCrossLinksChange={(links) => {
          setCrossLinks(links);
          setEntity(null);
        }}
        onRemoveTable={removeTable}
        onOpenFields={(sliceId, table) => {
          setFocusedFieldTable({ sliceId, table });
          fieldWorkspace.open();
        }}
      />

      <ApplicationDrawer
        opened={applicationOpened}
        editing={editing}
        existing={applications}
        onClose={applicationDrawer.close}
        onSave={saveApplication}
      />
      <CrossLinkDrawer
        opened={crossLinkOpened}
        applications={loadedApplications}
        links={crossLinks}
        onClose={crossLinkDrawer.close}
        onChange={(links) => {
          setCrossLinks(links);
          setEntity(null);
        }}
      />
      <MergeDrawer
        opened={mergeOpened}
        applications={loadedApplications}
        initialAnchors={anchors}
        saving={createEntity.isPending}
        onClose={mergeDrawer.close}
        onSave={(draft) => createEntity.mutate(draft)}
      />
      <FieldIntelligenceWorkspace
        opened={fieldWorkspaceOpened}
        applications={loadedApplications}
        crossLinks={crossLinks}
        rules={fieldRules}
        initialSelection={focusedFieldTable}
        readOnly={readOnly}
        onClose={fieldWorkspace.close}
        onSave={setFieldRules}
      />
      <Modal opened={createModelOpened} onClose={createModel.close} title="Create entity application" centered size="md">
        <Stack gap="md">
          <Text size="sm" c="dimmed">Publishing locks the selected sources, tables, relationships, and field rules. Use Edit entity application when a governed change is required.</Text>
          <TextInput
            label="Entity application name"
            description="8 to 100 characters"
            value={modelNameDraft}
            onChange={(event) => setModelNameDraft(event.currentTarget.value)}
            minLength={8}
            maxLength={100}
            spellCheck={false}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={createModel.close}>Cancel</Button>
            <Button
              color="teal"
              disabled={modelNameDraft.trim().length < 8 || modelNameDraft.trim().length > 100}
              onClick={() => {
                setModelName(modelNameDraft.trim());
                setModelStatus('CREATED');
                createModel.close();
                notifications.show({ color: 'green', title: 'Entity application published', message: 'The architecture is now read-only until Edit entity application is selected.' });
              }}
            >
              Publish and lock
            </Button>
          </Group>
        </Stack>
      </Modal>
    </main>
  );
}

function buildMembers(
  applications: LoadedApplication[],
  draft: EntityMergeDraft,
  crossLinks: CrossDatabaseLink[],
  fieldRules: ArchitectureFieldRule[]
): BusinessEntityMemberRequest[] {
  const primaryApplication = applications.find((application) => application.id === draft.primarySliceId);
  const primaryAnchor = draft.anchors.find((anchor) => anchor.sliceId === draft.primarySliceId);
  if (!primaryApplication || !primaryAnchor) return [];

  const roleByTable = new Map<string, string>();
  applications.forEach((application) => application.tables.forEach((table) => {
    roleByTable.set(memberMapKey(application.id, table), `${slug(application.label)}_${slug(table)}`);
  }));
  const primaryRole = roleByTable.get(memberMapKey(primaryApplication.id, primaryAnchor.table)) || slug(primaryAnchor.table);
  const members: BusinessEntityMemberRequest[] = [];
  let ordinal = 0;

  applications.forEach((application) => {
    const anchor = draft.anchors.find((candidate) => candidate.sliceId === application.id);
    const selectedTables = new Set(application.tables.map(normalize));
    application.tables.forEach((table) => {
      const role = roleByTable.get(memberMapKey(application.id, table)) || slug(table);
      const member: BusinessEntityMemberRequest = {
        systemName: application.label,
        dataSourceId: application.dataSourceId,
        schemaName: application.schema,
        logicalRole: role,
        tableName: table,
        includeInSubset: true,
        includeInSynthetic: true,
        ordinalNo: ordinal++
      };
      const memberFieldRules = fieldRules.filter((rule) =>
        rule.sliceId === application.id && normalize(rule.table) === normalize(table)
      );
      if (memberFieldRules.length) member.fieldRulesJson = JSON.stringify(memberFieldRules);

      const inboundLinks = crossLinks.filter((link) =>
        link.childSliceId === application.id && normalize(link.childTable) === normalize(table)
      );
      const outboundLinks = crossLinks.filter((link) =>
        link.parentSliceId === application.id && normalize(link.parentTable) === normalize(table)
      );
      const isPrimaryAnchor = application.id === draft.primarySliceId
        && anchor && normalize(table) === normalize(anchor.table);

      if (isPrimaryAnchor) {
        member.keyColumns = anchor.column;
      } else if (inboundLinks.length) {
        const inboundLink = inboundLinks[0];
        const parentApplication = applications.find((candidate) => candidate.id === inboundLink.parentSliceId);
        member.keyColumns = inboundLinks.map((link) => link.childColumn).join(',');
        member.joinToRole = roleByTable.get(memberMapKey(inboundLink.parentSliceId, inboundLink.parentTable)) || slug(inboundLink.parentTable);
        member.relationshipJson = JSON.stringify({
          name: `cross_${slug(parentApplication?.label || inboundLink.parentSliceId)}_${slug(application.label)}_${slug(table)}`,
          kind: inboundLink.kind,
          parentDataSourceId: parentApplication?.dataSourceId,
          parentSchema: parentApplication?.schema,
          parentTable: inboundLink.parentTable,
          parentColumns: inboundLinks.map((link) => link.parentColumn).join(','),
          childDataSourceId: application.dataSourceId,
          childSchema: application.schema,
          childTable: inboundLink.childTable,
          childColumns: inboundLinks.map((link) => link.childColumn).join(','),
          source: 'ENTITY_ARCHITECTURE'
        });
      } else if (anchor && normalize(table) === normalize(anchor.table)) {
        member.keyColumns = anchor.column;
        if (application.id !== draft.primarySliceId) {
          member.joinToRole = primaryRole;
          member.relationshipJson = JSON.stringify({
            name: `cross_${slug(primaryApplication.label)}_${slug(application.label)}`,
            parentTable: primaryAnchor.table,
            parentColumns: primaryAnchor.column,
            childTable: anchor.table,
            childColumns: anchor.column,
            source: 'ENTITY_CROSSWALK'
          });
        }
      } else if (outboundLinks.length) {
        member.keyColumns = outboundLinks.map((link) => link.parentColumn).join(',');
      } else {
        const relation = findParentRelation(application.graph?.edges || [], table, selectedTables);
        if (relation?.from && relation.to) {
          member.keyColumns = relation.fkColumn || null;
          member.joinToRole = roleByTable.get(memberMapKey(application.id, relation.from)) || slug(relation.from);
          member.relationshipJson = JSON.stringify({
            name: relation.id || `${relation.from}_${relation.to}`,
            parentTable: relation.from,
            parentColumns: relation.pkColumn || '',
            childTable: relation.to,
            childColumns: relation.fkColumn || '',
            source: 'DB_CATALOG'
          });
        }
      }
      members.push(member);
    });
  });
  return members;
}

function findParentRelation(edges: DiscoveryGraphEdge[], table: string, selected: Set<string>) {
  return edges.find((edge) => normalize(edge.to) === normalize(table) && selected.has(normalize(edge.from)));
}

function memberMapKey(sliceId: string, table: string) {
  return `${sliceId}|${normalize(table)}`;
}

function slug(value: string) {
  return normalize(value).replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'member';
}

function normalize(value?: string | null) {
  return String(value || '').trim().toLowerCase();
}
