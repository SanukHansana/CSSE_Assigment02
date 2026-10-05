import { Platform } from 'react-native';
import type { ImagePickerAsset } from 'expo-image-picker';
import { env } from '../../config/env';

export interface Account {
  displayName: string;
  roles: string[];
}
export interface Session {
  accessToken: string;
  user: Account;
}
export interface Report {
  id: string;
  reference: string;
  version: number;
  status: string;
  hazardType: string | null;
  description: string | null;
  location: { latitude: number; longitude: number; areaLabel?: string } | null;
  photo: { originalFilename: string; viewUrl: string } | null;
  history: { id: string; status: string; occurredAt: string }[];
}
export interface ReportPage {
  items: Report[];
  totalPages: number;
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  token?: string,
  method = 'GET',
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  const multipart = body instanceof FormData;
  const response = await fetch(`${env.apiBaseUrl}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body && !multipart ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  });
  const data = await response.json();
  if (!response.ok) {
    const fields = Array.isArray(data.fieldErrors)
      ? data.fieldErrors
          .map(
            (field: { field: string; message?: string }) =>
              `${field.field}${field.message ? `: ${field.message}` : ''}`,
          )
          .join(', ')
      : '';
    throw new ApiError(
      `${data.message || 'Request failed.'}${fields ? ` (${fields})` : ''}`,
      response.status,
    );
  }
  return data as T;
}
export async function uploadPhoto(report: Report, photo: ImagePickerAsset, token: string) {
  const form = new FormData();
  if (Platform.OS === 'web') {
    const file = photo.file || (await (await fetch(photo.uri)).blob());
    form.append('file', file, photo.fileName || 'evidence.jpg');
  } else {
    // React Native's FormData accepts a local URI file descriptor.
    form.append('file', {
      uri: photo.uri,
      name: photo.fileName || 'evidence.jpg',
      type: photo.mimeType || 'image/jpeg',
    } as unknown as Blob);
  }
  return request<Report>(`/api/dmc/ground-reports/${report.id}/photo`, token, 'PUT', form, {
    'If-Match': `"${report.version}"`,
  });
}
