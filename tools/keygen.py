#!/usr/bin/env python3
"""JianNotePremium 프리미엄 해제 코드 생성기.
사용법: python3 keygen.py <앱 프리미엄 화면에 표시된 기기코드 8자리>
"""
import hashlib, sys

SECRET = "JianNote-2026-kjt7942-premium"

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit(1)
dev = sys.argv[1].strip().upper()
h = hashlib.sha256(f"{SECRET}:{dev}".encode()).hexdigest()[:12].upper()
print(f"{h[0:4]}-{h[4:8]}-{h[8:12]}")
