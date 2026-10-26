import { describe, expect, it } from 'vitest'
import {
  DESKTOP_ADMIN_HEADER,
  DESKTOP_ADMIN_MARKER,
  WEB_ADMIN_HEADER,
  WEB_ADMIN_MARKER,
  isDesktopAdminSurface,
  isLocalAdminHostname,
  localAdminRequestHeaders,
} from '../src/local-admin-host.js'

describe('desktop admin hostnames', () => {
  it.each([
    'localhost',
    'LOCALHOST',
    '127.0.0.1',
    '127.255.255.255',
    '::1',
    '[::1]',
    '10.0.0.1',
    '10.255.255.254',
    '172.16.0.1',
    '172.31.255.255',
    '192.168.0.1',
    '169.254.1.1',
  ])('accepts %s', (hostname) => {
    expect(isLocalAdminHostname(hostname)).toBe(true)
  })

  it.each([
    '',
    'example.com',
    'evil.example',
    'dsh.example.com',
    '8.8.8.8',
    '1.1.1.1',
    '100.64.0.1',
    '100.127.255.254',
    '203.0.113.10',
    '192.0.2.1',
    '169.253.0.1',
    '172.15.0.1',
    '172.32.0.1',
    '0.0.0.0',
    '255.255.255.255',
    '192.168.1',
    '192.168.001.1',
    'localhost.example',
    '::ffff:192.168.1.1',
    'fc00::1',
    'fe80::1',
  ])('rejects %s', (hostname) => {
    expect(isLocalAdminHostname(hostname)).toBe(false)
  })
})

describe('desktop admin surface', () => {
  it('treats private LAN DSH Web as the desktop admin surface', () => {
    expect(isDesktopAdminSurface('localhost')).toBe(true)
    expect(isDesktopAdminSurface('127.0.0.1')).toBe(true)
    expect(isDesktopAdminSurface('192.168.50.23')).toBe(true)
    expect(isDesktopAdminSurface('10.0.0.8', '')).toBe(true)
  })

  it('keeps the dedicated Mobile HTTPS frontend on the phone surface', () => {
    expect(isDesktopAdminSurface('192.168.50.23', '', 'dedicated')).toBe(false)
    expect(isDesktopAdminSurface('localhost', '', 'dedicated')).toBe(false)
  })

  it('keeps the preview query on the phone surface', () => {
    expect(isDesktopAdminSurface('localhost', '?dsh-mobile-preview')).toBe(false)
    expect(isDesktopAdminSurface('192.168.50.23', 'dsh-mobile-preview=1')).toBe(false)
  })

  it('rejects public and DNS-rebinding Host values', () => {
    expect(isDesktopAdminSurface('evil.example')).toBe(false)
    expect(isDesktopAdminSurface('8.8.8.8')).toBe(false)
  })

  it('treats the dsh-app desktop-shell protocol as the desktop admin surface', () => {
    expect(isDesktopAdminSurface('app', '', undefined, 'dsh-app:')).toBe(true)
    expect(isDesktopAdminSurface('app', '?dsh-mobile-preview', undefined, 'dsh-app:')).toBe(false)
    expect(isDesktopAdminSurface('app', '', 'dedicated', 'dsh-app:')).toBe(false)
  })

  it('keeps non-shell hostnames protocol-gated', () => {
    expect(isDesktopAdminSurface('evil.example', '', undefined, 'dsh-app:')).toBe(false)
    expect(isDesktopAdminSurface('localhost', '', undefined, 'https:')).toBe(true)
    expect(isDesktopAdminSurface('localhost', '', undefined, 'dsh-app:')).toBe(false)
    expect(isDesktopAdminSurface('localhost', '', undefined, 'file:')).toBe(false)
  })
})

describe('local admin request headers', () => {
  it('marks only mutating requests from the exact official Desktop document', () => {
    const headers = localAdminRequestHeaders({ method: 'POST' }, new URL('dsh-app://app/'))
    expect(headers.get(DESKTOP_ADMIN_HEADER)).toBe(DESKTOP_ADMIN_MARKER)
    expect(headers.get('content-type')).toBe('application/json')
    for (const [method, url] of [
      ['GET', 'dsh-app://app/'], ['POST', 'dsh-app://other/'],
      ['POST', 'http://127.0.0.1:3080/'], ['POST', 'https://app/'],
    ] as const) {
      expect(localAdminRequestHeaders({ method }, new URL(url)).has(DESKTOP_ADMIN_HEADER)).toBe(false)
    }
  })

  it('does not forward a caller-provided marker on other surfaces', () => {
    const init = { method: 'POST', headers: new Headers({
      [DESKTOP_ADMIN_HEADER]: DESKTOP_ADMIN_MARKER,
      'content-type': 'application/problem+json',
    }) }
    const headers = localAdminRequestHeaders(init, new URL('http://127.0.0.1:3080/'))
    expect(headers.has(DESKTOP_ADMIN_HEADER)).toBe(false)
    expect(headers.get('content-type')).toBe('application/problem+json')
  })

  it.each(['http://127.0.0.1:3080/', 'http://192.168.50.23:3080/', 'https://localhost:3080/'])('marks a JSON POST on the trusted Web admin surface %s', url => {
    const headers = localAdminRequestHeaders({ method: 'post' }, new URL(url))
    expect(headers.get(WEB_ADMIN_HEADER)).toBe(WEB_ADMIN_MARKER)
    expect(headers.has(DESKTOP_ADMIN_HEADER)).toBe(false)
    expect(headers.get('content-type')).toBe('application/json')
  })

  it.each([
    ['GET', 'http://127.0.0.1:3080/', 'application/json'],
    ['POST', 'http://127.0.0.1:3080/', 'text/plain'],
    ['POST', 'http://127.0.0.1:3080/', 'application/problem+json'],
    ['POST', 'https://evil.example/', 'application/json'],
    ['POST', 'https://8.8.8.8/', 'application/json'],
    ['POST', 'file:///owned.html', 'application/json'],
    ['POST', 'dsh-app://other/', 'application/json'],
  ])('does not add a Web proof for %s %s with %s', (method, url, contentType) => {
    const original = new Headers({ [WEB_ADMIN_HEADER]: WEB_ADMIN_MARKER, [DESKTOP_ADMIN_HEADER]: DESKTOP_ADMIN_MARKER, 'content-type': contentType })
    const headers = localAdminRequestHeaders({ method, headers: original }, new URL(url))
    expect(headers.has(WEB_ADMIN_HEADER)).toBe(false)
    expect(headers.has(DESKTOP_ADMIN_HEADER)).toBe(false)
    expect(original.get(WEB_ADMIN_HEADER)).toBe(WEB_ADMIN_MARKER)
    expect(original.get(DESKTOP_ADMIN_HEADER)).toBe(DESKTOP_ADMIN_MARKER)
  })

  it('keeps Desktop authentication and Web source markers distinct', () => {
    const headers = localAdminRequestHeaders({ method: 'POST', headers: { [WEB_ADMIN_HEADER]: WEB_ADMIN_MARKER } }, new URL('dsh-app://app/'))
    expect(headers.has(WEB_ADMIN_HEADER)).toBe(false)
    expect(headers.get(DESKTOP_ADMIN_HEADER)).toBe(DESKTOP_ADMIN_MARKER)
  })

  it('recognizes a case-insensitive JSON media type with parameters', () => {
    const headers = localAdminRequestHeaders({ method: 'POST', headers: { 'content-type': 'Application/JSON; charset=utf-8' } }, new URL('http://127.0.0.1:3080/'))
    expect(headers.get(WEB_ADMIN_HEADER)).toBe(WEB_ADMIN_MARKER)
  })
})
