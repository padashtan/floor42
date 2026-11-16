import type {
  MeetingInvitation,
  MeetingNoteUse,
  MeetingPlace,
  MeetingView,
} from '../types'
import { request } from '../request'

export const meetingsApi = {
  // director-meeting (ADR-0047)
  /** The open meetings and the latest closed, ten in all (ADR-0049): read every few seconds. */
  meetings: () => request<MeetingView[]>('GET', '/api/meetings?limit=10'),
  meetingInvitation: (mission: string) =>
    request<MeetingInvitation>('GET', `/api/meetings/invitation?mission=${encodeURIComponent(mission)}`),
  callMeeting: (mission: string, attendees: string[], agenda: string | null, place: MeetingPlace = 'TABLE') =>
    request<MeetingView>('POST', '/api/meetings', { mission, attendees, agenda, place }),
  askForViews: (meeting: string, speakers: string[], capUsd: number, question: string | null) =>
    request<MeetingView>('POST', `/api/meetings/${encodeURIComponent(meeting)}/views`, { speakers, capUsd, question }),
  meetingNote: (meeting: string, attendee: string | null, text: string, use: MeetingNoteUse) =>
    request<MeetingView>('POST', `/api/meetings/${encodeURIComponent(meeting)}/notes`, { attendee, text, use }),
  closeMeeting: (meeting: string) =>
    request<MeetingView>('POST', `/api/meetings/${encodeURIComponent(meeting)}/close`),
}
