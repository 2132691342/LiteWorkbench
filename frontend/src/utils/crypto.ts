import JSEncrypt from 'jsencrypt'
import { authApi } from '@/api'
import type { PublicKeyVO } from '@/types/api'

/**
 * 凭据加密：公钥由服务端下发，用 RSA/PKCS#1 v1.5 加密后提交。
 * 加密失败一律报错，绝不降级为明文。
 */

/** 本地缓存的公钥，临期前 30 秒视为过期 */
let cached: PublicKeyVO | null = null
let inflight: Promise<PublicKeyVO> | null = null

const EXPIRE_SKEW_MS = 30_000

/** 取公钥，带缓存与并发合并 */
export async function fetchPublicKey(): Promise<PublicKeyVO> {
  if (cached && cached.expiresAt - Date.now() > EXPIRE_SKEW_MS) {
    return cached
  }
  if (!inflight) {
    inflight = authApi
      .publicKey()
      .then((key) => {
        cached = key
        return key
      })
      .finally(() => {
        inflight = null
      })
  }
  return inflight
}

/** 加密明文，返回 { keyId, cipher } */
export async function encryptSecret(plain: string): Promise<{ keyId: string; cipher: string }> {
  const key = await fetchPublicKey()
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(toPem(key.publicKey))
  const cipher = encryptor.encrypt(plain)
  if (!cipher || typeof cipher !== 'string') {
    throw new Error('SECRET_ENCRYPT_FAILED')
  }
  return { keyId: key.keyId, cipher }
}

/** 丢弃缓存，改密或轮换后调用 */
export function resetPublicKey(): void {
  cached = null
}

/** SPKI Base64 补成 PEM，JSEncrypt 认 PEM 头 */
function toPem(base64: string): string {
  if (base64.includes('-----BEGIN')) {
    return base64
  }
  const lines = base64.match(/.{1,64}/g) ?? [base64]
  return `-----BEGIN PUBLIC KEY-----\n${lines.join('\n')}\n-----END PUBLIC KEY-----`
}
