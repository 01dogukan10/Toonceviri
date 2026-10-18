# ToonÇeviri

Tamamen ücretsiz ve cihaz içinde çalışan manga / manhwa / webtoon çevirici.

## Ne yapıyor?
- **Linkten bölüm çekme:** Bölüm sayfasının linkini yapıştır (ya da tarayıcıdan *Paylaş → ToonÇeviri*). Sayfadaki resimler indirilir.
- **Galeriden çeviri:** Link çalışmazsa ekran görüntülerini ya da indirdiğin sayfaları seç.
- **Balon temizleme ve içine yazma:** Yazılar ML Kit ile okunur, balon rengiyle silinir. Çeviri balonun içine sığacak boyutta, ortalanarak yazılır.
- **Kütüphane ve çevrimdışı okuma:** Çevrilen bölümler cihazda saklanır. Dikey okuyucuda internetsiz okunur, orijinal/çeviri arasında geçiş yapılabilir.

## Dil desteği
- Kaynak: İngilizce, Türkçe, Korece, Japonca, Çince ve Latin alfabeli diğer diller
- Hedef: yukarıdakiler + Rusça, Arapça, Tayca
- İlk çeviride dil modeli (~30 MB) indirilir, sonrası internetsiz.

## APK alma
En son APK her zaman **Releases** kısmındadır. İndirip telefona kurman yeterli. Yeni sürümler eskisinin üstüne güncelleme olarak kurulur.

## Sürümleme
- Sürüm numarası `version.txt` dosyasında, değişiklik notu `RELEASE_NOTES.md` dosyasında tutulur.
- Her değişiklikte numara artırılır ve notlar güncellenir. Push sonrası Actions APK'yı derler ve yeni bir Release yayınlar.
- Test dönemi: 0.10, 0.11, 0.12 …
- Kararlı sürüm: 1.0, ardından küçük güncellemeler 1.1, 1.2 …

## Bilinen sınırlar
- Resimleri JavaScript ile yükleyen sitelerde linkten çekme çalışmaz; galeri yolunu kullan.
- Dikey Japonca yazıda OCR zayıf kalabilir.
- Ücretsiz çeviri modeli makine çevirisidir; esprileri ve argoyu her zaman tutturamaz.
