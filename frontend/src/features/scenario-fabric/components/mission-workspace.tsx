'use client';

import {
  Accordion,
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Loader,
  Modal,
  Progress,
  ScrollArea,
  SimpleGrid,
  Stack,
  Text,
  Timeline
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconActivity,
  IconArrowRight,
  IconCheck,
  IconClipboardCheck,
  IconPlayerPlay,
  IconRefresh,
  IconShieldCheck,
  IconX
} from '@tabler/icons-react';

import { usePermissions } from '@/lib/use-permissions';
import { useScenarioActions, useScenarioMission } from '../hooks';
import { asList, asMap, errorMessage, formatCount, formatWhen, numberValue, statusColor, textValue } from '../utils';

export function MissionWorkspace({
  missionId,
  onClose
}: {
  missionId: string | null;
  onClose: () => void;
}) {
  const { can } = usePermissions();
  const actions = useScenarioActions();
  const missionQuery = useScenarioMission(missionId);
  const mission = missionQuery.data;
  const steps = asList(mission?.plan.steps);
  const checks = asList(mission?.verification.checks);
  const handles = asList(mission?.readyPack.scenarioHandles);
  const canLaunch = can('scenario.run') && Boolean(mission) &&
    !['RUNNING', 'READY', 'READY_WITH_WARNINGS', 'FAILED', 'CANCELLED'].includes(mission?.status || '');
  const progress = mission?.status === 'READY' || mission?.status === 'READY_WITH_WARNINGS'
    ? 100
    : mission?.status === 'RUNNING'
      ? 65
    : ['WAITING_APPROVAL', 'APPROVED'].includes(mission?.status || '')
        ? 25
        : 0;

  const launch = async () => {
    if (!mission) return;
    try {
      const result = await actions.launchMission.mutateAsync(mission.id);
      notifications.show({
        color: result.status === 'WAITING_APPROVAL' ? 'yellow' : 'blue',
        title: result.status === 'WAITING_APPROVAL' ? 'Mission submitted' : 'Mission launched',
        message: result.status === 'WAITING_APPROVAL'
          ? 'The self-service request is waiting at its configured approval gate.'
          : 'Live engine status is now attached to this Mission.'
      });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not launch Mission', message: errorMessage(error) });
    }
  };

  const refresh = async () => {
    if (!mission) return;
    try {
      await actions.refreshMission.mutateAsync(mission.id);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not refresh Mission', message: errorMessage(error) });
    }
  };

  return (
    <Modal opened={Boolean(missionId)} onClose={onClose} fullScreen padding={0} title={null}>
      <div className="scenario-fullscreen">
        <header className="scenario-fullscreen-head">
          <Group gap="sm">
            <span className="scenario-page-mark"><IconClipboardCheck size={20} /></span>
            <div>
              <Group gap="xs">
                <Text fw={850} size="lg">{mission?.title || 'Test data mission'}</Text>
                {mission ? <Badge color={statusColor(mission.status)} variant="light">{mission.status.replaceAll('_', ' ')}</Badge> : null}
              </Group>
              <Text size="sm" c="dimmed">{mission ? `${mission.domainName} / ${mission.blueprintName} v${mission.blueprintVersion}` : 'Loading mission'}</Text>
            </div>
          </Group>
          <Group gap="xs">
            {mission?.selfServiceOrderId ? <Code>{mission.selfServiceOrderId.slice(0, 8)}</Code> : null}
            <Button variant="light" leftSection={<IconRefresh size={16} />} loading={actions.refreshMission.isPending} disabled={!mission?.selfServiceOrderId} onClick={refresh}>Refresh status</Button>
            <Button leftSection={<IconPlayerPlay size={16} />} loading={actions.launchMission.isPending} disabled={!canLaunch} onClick={launch}>
              {['WAITING_APPROVAL', 'APPROVED'].includes(mission?.status || '') ? 'Launch approved request' : 'Launch mission'}
            </Button>
            <Button variant="subtle" color="gray" onClick={onClose} aria-label="Close"><IconX size={20} /></Button>
          </Group>
        </header>

        {missionQuery.isLoading ? (
          <div className="scenario-loading"><Loader size="sm" /> Compiling Mission evidence</div>
        ) : mission ? (
          <ScrollArea className="scenario-mission-scroll">
            <main className="scenario-mission-detail">
              <section className="scenario-mission-hero">
                <div>
                  <Text size="xs" fw={800} c="blue" tt="uppercase">Test objective</Text>
                  <Text fw={750} size="lg">{mission.intent}</Text>
                  <Group gap="xs" mt="sm">
                    <Badge variant="outline">{mission.sourceStrategy}</Badge>
                    <Badge variant="outline">{mission.targetEnvironment || 'Environment not set'}</Badge>
                    <Badge variant="outline">{formatCount(mission.requestedCount)} requested</Badge>
                    <Badge variant="outline">{numberValue(mission.coverage.caseCount)} cases</Badge>
                  </Group>
                </div>
                <div className="scenario-progress-block">
                  <Group justify="space-between"><Text fw={750}>Mission readiness</Text><Text fw={850}>{progress}%</Text></Group>
                  <Progress value={progress} size="lg" animated={mission.status === 'RUNNING'} />
                  <Text size="xs" c="dimmed">
                    {mission.events.at(-1)?.message || 'Coverage plan is retained and ready for review.'}
                  </Text>
                </div>
              </section>

              {mission.status === 'NEEDS_BINDING' ? (
                <Alert color="yellow" title="Delivery binding required">
                  Open Test Domains and attach an approved Self-Service product. This coverage plan remains saved.
                </Alert>
              ) : null}
              {mission.status === 'WAITING_APPROVAL' ? (
                <Alert color="yellow" title="Maker-checker gate">
                  The underlying Self-Service request must be approved by an authorized reviewer. After approval, use "Launch approved request".
                </Alert>
              ) : null}

              <SimpleGrid cols={{ base: 1, xl: 2 }} spacing="lg">
                <section className="scenario-section">
                  <div className="scenario-section-head">
                    <div><Text fw={800}>Execution story</Text><Text size="sm" c="dimmed">What ForgeTDM will do, in business-readable order.</Text></div>
                  </div>
                  <div className="scenario-step-list">
                    {steps.map((value, index) => {
                      const step = asMap(value);
                      return (
                        <div key={`${textValue(step.code)}-${index}`}>
                          <span>{index + 1}</span>
                          <div><strong>{textValue(step.title, textValue(step.code))}</strong><small>{textValue(step.description)}</small></div>
                          <IconArrowRight size={15} />
                        </div>
                      );
                    })}
                  </div>
                </section>

                <section className="scenario-section">
                  <div className="scenario-section-head">
                    <div><Text fw={800}>Coverage contract</Text><Text size="sm" c="dimmed">Generated cases are deterministic and retained with this Mission.</Text></div>
                    <Badge variant="light">{mission.cases.length} cases</Badge>
                  </div>
                  <Group gap="xs" mb="sm">
                    {asList(mission.coverage.techniques).map((technique) => <Badge key={textValue(technique)} variant="outline">{textValue(technique)}</Badge>)}
                  </Group>
                  <Accordion variant="separated" radius="sm">
                    {mission.cases.map((item) => (
                      <Accordion.Item value={item.caseKey} key={item.caseKey}>
                        <Accordion.Control icon={<Badge size="xs" variant="light">{item.ordinal}</Badge>}>
                          <Group justify="space-between" pr="sm"><Text fw={700} size="sm">{item.title}</Text><Badge color="gray" variant="light">{item.caseKind.replaceAll('_', ' ')}</Badge></Group>
                        </Accordion.Control>
                        <Accordion.Panel>
                          <Text size="xs" fw={800} c="dimmed" mb={4}>INPUT STATE</Text>
                          <Code block>{JSON.stringify(item.inputs, null, 2)}</Code>
                          <Text size="xs" fw={800} c="dimmed" mt="sm" mb={4}>EXPECTED</Text>
                          <Code block>{JSON.stringify(item.expected, null, 2)}</Code>
                        </Accordion.Panel>
                      </Accordion.Item>
                    ))}
                  </Accordion>
                </section>
              </SimpleGrid>

              <SimpleGrid cols={{ base: 1, xl: 2 }} spacing="lg">
                <section className="scenario-section">
                  <div className="scenario-section-head">
                    <div><Text fw={800}>Verification</Text><Text size="sm" c="dimmed">Proof collected from the actual delivery engine.</Text></div>
                    {mission.verification.status ? <Badge color={statusColor(textValue(mission.verification.status))}>{textValue(mission.verification.status).replaceAll('_', ' ')}</Badge> : null}
                  </div>
                  {checks.length ? (
                    <div className="scenario-check-list">
                      {checks.map((value, index) => {
                        const check = asMap(value);
                        const passed = Boolean(check.passed);
                        return <div key={`${textValue(check.code)}-${index}`} className={passed ? 'is-pass' : 'is-fail'}><span>{passed ? <IconCheck size={15} /> : <IconX size={15} />}</span><div><strong>{textValue(check.code).replaceAll('_', ' ')}</strong><small>{textValue(check.evidence)}</small></div></div>;
                      })}
                    </div>
                  ) : <Text size="sm" c="dimmed">Verification begins when the delivery engine reports a terminal result.</Text>}
                </section>

                <section className="scenario-section">
                  <div className="scenario-section-head">
                    <div><Text fw={800}>Ready-to-Test pack</Text><Text size="sm" c="dimmed">One handoff containing delivery, case handles, lineage, and lifecycle controls.</Text></div>
                    <IconShieldCheck size={20} />
                  </div>
                  {Object.keys(mission.readyPack).length ? (
                    <Stack gap="sm">
                      <SimpleGrid cols={3}>
                        <PackFact label="Run" value={textValue(mission.readyPack.runRef, 'Completed')} />
                        <PackFact label="Rows" value={formatCount(numberValue(mission.readyPack.rowsWritten))} />
                        <PackFact label="Ready" value={formatWhen(textValue(mission.readyPack.readyAt))} />
                      </SimpleGrid>
                      <Text fw={750} size="sm">Scenario handles</Text>
                      <div className="scenario-handle-list">
                        {handles.slice(0, 8).map((value, index) => {
                          const handle = asMap(value);
                          return <div key={`${textValue(handle.caseKey)}-${index}`}><Code>{textValue(handle.caseKey)}</Code><span>{textValue(handle.scenario)}</span></div>;
                        })}
                      </div>
                    </Stack>
                  ) : <Text size="sm" c="dimmed">The pack appears after successful delivery and technical verification.</Text>}
                </section>
              </SimpleGrid>

              <section className="scenario-section">
                <div className="scenario-section-head"><div><Text fw={800}>Mission activity</Text><Text size="sm" c="dimmed">Immutable planning, approval, execution, and evidence trail.</Text></div></div>
                <Timeline active={mission.events.length} bulletSize={24} lineWidth={2}>
                  {mission.events.map((event) => (
                    <Timeline.Item key={event.id} bullet={<IconActivity size={13} />} title={event.eventType.replaceAll('_', ' ')}>
                      <Text size="sm">{event.message}</Text>
                      <Text size="xs" c="dimmed">{event.actor} / {formatWhen(event.createdAt)}</Text>
                    </Timeline.Item>
                  ))}
                </Timeline>
              </section>
            </main>
          </ScrollArea>
        ) : (
          <Alert color="red">The Mission could not be loaded.</Alert>
        )}
      </div>
    </Modal>
  );
}

function PackFact({ label, value }: { label: string; value: string }) {
  return <div className="scenario-pack-fact"><span>{label}</span><strong>{value}</strong></div>;
}
