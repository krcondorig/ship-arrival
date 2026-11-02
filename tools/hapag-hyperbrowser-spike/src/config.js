const TRACKING_URL = 'https://www.hapag-lloyd.com/en/online-business/track/track-by-booking.html';

export function readConfig(env) {
  if (!env.HYPERBROWSER_API_KEY?.trim()) throw new Error('HYPERBROWSER_API_KEY is required');
  if (!env.HAPAG_BL?.trim()) throw new Error('HAPAG_BL is required');
  const timeoutMinutes = Number(env.HAPAG_SPIKE_TIMEOUT_MINUTES ?? '10');
  if (!Number.isInteger(timeoutMinutes) || timeoutMinutes < 1 || timeoutMinutes > 720) {
    throw new Error('timeout must be an integer from 1 to 720 minutes');
  }
  return { apiKey: env.HYPERBROWSER_API_KEY, bl: env.HAPAG_BL.trim(), timeoutMinutes };
}

export function trackingUrl(bl) {
  const url = new URL(TRACKING_URL);
  url.searchParams.set('blno', bl);
  return url.toString();
}

export function sessionOptions(timeoutMinutes) {
  return { timeoutMinutes };
}
