/**
 * Copyright (c) 2003-2018 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sakaiproject.certification.impl;

import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.AcroFields;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.sakaiproject.certification.api.CertificateService;
import org.sakaiproject.certification.api.DocumentTemplate;
import org.sakaiproject.certification.api.DocumentTemplateRenderEngine;
import org.sakaiproject.certification.api.DocumentTemplateService;
import org.sakaiproject.certification.api.TemplateReadException;

public class ITextDocumentTemplateRenderEngine implements DocumentTemplateRenderEngine {

    private static final String MIME_TYPE = "application/pdf";
    private DocumentTemplateService documentTemplateService = null;
    private CertificateService certificateService = null;

    public void setDocumentTemplateService(DocumentTemplateService dts) {
        this.documentTemplateService = (DocumentTemplateService) dts;
    }

    public DocumentTemplateService getDocumentTemplateService() {
        return documentTemplateService;
    }

    public CertificateService getCertificateService() {
        return certificateService;
    }

    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    public void init() {
        getDocumentTemplateService().register(MIME_TYPE, this);
    }

    public String getOutputMimeType(DocumentTemplate template) {
        return MIME_TYPE;
    }

    private void assertCorrectType(final DocumentTemplate template) throws TemplateReadException {
        final String mimeType = template.getOutputMimeType();
        if (!MIME_TYPE.equalsIgnoreCase(mimeType)) {
            throw new TemplateReadException("incorrect mime type: " + mimeType);
        }
    }

    public Set<String> getTemplateFields(DocumentTemplate template) throws TemplateReadException {
        assertCorrectType(template);
        return getTemplateFields(certificateService.getTemplateFileInputStream(template.getResourceId()));
    }

    public Set<String> getTemplateFields(InputStream inputStream) throws TemplateReadException {
        try {
            PdfReader reader = new PdfReader(inputStream);
            AcroFields acroFields = reader.getAcroFields();
            Map<String, AcroFields.Item> fields = acroFields.getFields();

            Set<String> textFieldKeys = new HashSet<>();
            for (String key : fields.keySet()) {
                if (acroFields.getFieldType(key) == AcroFields.FIELD_TYPE_TEXT) {
                    textFieldKeys.add(key);
                }
            }
            return textFieldKeys;

        } catch (IOException e) {
            throw new TemplateReadException(e);
        }
    }

    /**
     * Türkçe büyük harflerin (Ğ, İ, Ş vb.) PDF'e doğru yazılması için:
     *
     * SORUN: Cp1254 encoding Ğ (U+011E), İ (U+0130), Ş (U+015E) gibi
     *        Türkçe büyük harfleri iText ile doğru map edemez.
     *
     * ÇÖZÜM: IDENTITY_H encoding kullanılır (tüm Unicode desteklenir) +
     *        setGenerateAppearances(true) ile iText PDF görünümünü
     *        Acrobat'ın gömülü fontunu bypass ederek sıfırdan çizer.
     */
    public InputStream render(DocumentTemplate template, Map<String, String> bindings) throws TemplateReadException {
        assertCorrectType(template);

        try {
            PdfReader reader = new PdfReader(certificateService.getTemplateFileInputStream(template.getResourceId()));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, baos);

            stamper.setFormFlattening(true);
            stamper.setFreeTextFlattening(true);

            AcroFields form = stamper.getAcroFields();

            // KRİTİK: iText'e "PDF'teki mevcut görünümü kullanma, sıfırdan çiz" dedirtir.
            // Bu olmadan Acrobat'ın gömülü font ayarları override edilemez.
            form.setGenerateAppearances(true);

            try {
                // IDENTITY_H: Unicode'un tamamını destekler (Ğ, İ, Ş dahil)
                // Cp1254 bu büyük harfleri iText ile doğru map edemediği için IDENTITY_H kullanıyoruz.
                String dejavuPath = "/usr/local/tomcat/conf/DejaVuSans.ttf";
                BaseFont dejavuFont = BaseFont.createFont(dejavuPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                String serifPath = "/usr/local/tomcat/conf/LiberationSerif-Regular.ttf";
                BaseFont serifFont = BaseFont.createFont(serifPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                // Tüm form alanlarını işle
                for (String key : form.getFields().keySet()) {
                    // Acrobat'ın gömülü font kilidini kır, DejaVu'yu zorla ata
                    form.setFieldProperty(key, "textfont", dejavuFont, null);

                    String binding = bindings.get(key);
                    if (binding != null) {
                        // Unicode NFC normalizasyonu: bileşik karakterleri tek kod noktasına indirir
                        binding = Normalizer.normalize(binding, Normalizer.Form.NFC);
                    } else {
                        binding = "";
                    }

                    System.out.println("[CERT DEBUG] key=" + key + " value=" + binding);
                    form.setField(key, binding);
                }

                // DejaVu bir karakteri çizemezse yedek olarak serifFont devreye girer
                form.addSubstitutionFont(serifFont);

            } catch (Exception e) {
                System.err.println("[CERTIFICATION FONT ERROR] Font cozumu basarisiz, standart yazma deneniyor: " + e.getMessage());
                e.printStackTrace();
                // Hata olursa NFC normalizasyonu ile standart yoldan yazmayı dene
                for (String key : form.getFields().keySet()) {
                    String binding = bindings.get(key);
                    if (binding != null) {
                        binding = Normalizer.normalize(binding, Normalizer.Form.NFC);
                    } else {
                        binding = "";
                    }
                    form.setField(key, binding);
                }
            }

            stamper.close();
            return new ByteArrayInputStream(baos.toByteArray());

        } catch (IOException | DocumentException e) {
            throw new TemplateReadException(e);
        }
    }

    public boolean supportsPreview(DocumentTemplate template) throws TemplateReadException {
        assertCorrectType(template);
        return true;
    }

    public String getPreviewMimeType(DocumentTemplate template) throws TemplateReadException {
        assertCorrectType(template);
        return MIME_TYPE;
    }

    public InputStream renderPreview(DocumentTemplate template, Map<String, String> bindings) throws TemplateReadException {
        assertCorrectType(template);
        try {
            PdfReader reader = new PdfReader(certificateService.getTemplateFileInputStream(template.getResourceId()));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, baos);

            stamper.setFormFlattening(true);
            stamper.setFreeTextFlattening(true);
            stamper.close();

            return new ByteArrayInputStream(baos.toByteArray());

        } catch (IOException | DocumentException e) {
            throw new TemplateReadException(e);
        }
    }
}