import { afterEach, beforeEach, describe, expect, test } from 'bun:test';

import type { Location } from './definitions';
import { BackgroundGeolocationWeb } from './web';

const makePosition = (latitude: number, longitude: number, timestamp: number): GeolocationPosition =>
  ({
    coords: {
      latitude,
      longitude,
      accuracy: 1,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
    },
    timestamp,
  }) as GeolocationPosition;

describe('BackgroundGeolocationWeb interval forwarding generation', () => {
  let watchSuccess: ((position: GeolocationPosition) => void) | undefined;
  let pendingGetCurrent:
    | {
        success: PositionCallback;
        error: PositionCallback;
      }
    | undefined;
  let plugin: BackgroundGeolocationWeb;

  beforeEach(() => {
    watchSuccess = undefined;
    pendingGetCurrent = undefined;
    plugin = new BackgroundGeolocationWeb();
    globalThis.navigator = {
      geolocation: {
        watchPosition: (success) => {
          watchSuccess = success;
          return 1;
        },
        clearWatch: () => undefined,
        getCurrentPosition: (success, error) => {
          pendingGetCurrent = { success, error };
        },
      },
    } as Navigator;
  });

  afterEach(async () => {
    await plugin.stop();
  });

  test('ignores superseded interval getCurrentPosition success after watch forwards', async () => {
    const deliveries: Location[] = [];
    await plugin.start({ intervalMs: 20 }, (location) => {
      if (location) {
        deliveries.push(location);
      }
    });

    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(pendingGetCurrent).toBeDefined();

    watchSuccess?.(makePosition(10, 20, 200));
    expect(deliveries).toHaveLength(1);
    expect(deliveries[0]?.latitude).toBe(10);

    pendingGetCurrent?.success(makePosition(1, 2, 50));
    expect(deliveries).toHaveLength(1);
    expect(deliveries[0]?.latitude).toBe(10);
  });

  test('ignores superseded interval getCurrentPosition error after watch forwards', async () => {
    let deliveryCount = 0;
    await plugin.start({ intervalMs: 20 }, (location) => {
      if (location) {
        deliveryCount++;
      }
    });

    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(pendingGetCurrent).toBeDefined();

    watchSuccess?.(makePosition(5, 6, 300));
    expect(deliveryCount).toBe(1);

    pendingGetCurrent?.error({
      code: 2,
      message: 'timeout',
      PERMISSION_DENIED: 1,
      POSITION_UNAVAILABLE: 2,
      TIMEOUT: 3,
    });

    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(deliveryCount).toBe(1);
  });
});
