# ToonÇeviri

OpenToon tarzı, tamamen ücretsiz ve cihaz içinde çalışan manga / manhwa / webtoon çevirici.

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
1. Bu klasörü yeni bir GitHub reposuna yükle.
2. **Actions** sekmesinde "APK derle" işi otomatik çalışır.
3. Bittiğinde **Artifacts → ToonCeviri-apk** dosyasını indir, zipten çıkar, telefona kur.

## Bilinen sınırlar
- Resimleri JavaScript ile yükleyen sitelerde linkten çekme çalışmaz; galeri yolunu kullan.
- Dikey Japonca yazıda OCR zayıf kalabilir.
- Ücretsiz çeviri modeli makine çevirisidir; esprileri ve argoyu her zaman tutturamaz.
