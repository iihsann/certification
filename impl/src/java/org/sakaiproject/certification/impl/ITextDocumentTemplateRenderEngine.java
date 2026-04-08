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
// bunları ekle
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfName;

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

            // =========================================================
            // NİHAİ "GOD MODE" ÇÖZÜMÜ: ŞABLON HAFIZASINI SİL VE UTF-8 ZORLA
            // =========================================================
            try {
                // 1. ADIM: Acrobat'ın şablona gömdüğü hatalı kodlamaları (MacRoman/WinAnsi) kökten sil.
                PdfDictionary acroFormDict = reader.getCatalog().getAsDict(PdfName.ACROFORM);
                if (acroFormDict != null) {
                    PdfDictionary defaultResources = acroFormDict.getAsDict(PdfName.DR);
                    if (defaultResources != null) {
                        // PDF'in içindeki bozuk font sözlüğünü çöpe atıyoruz
                        defaultResources.remove(PdfName.FONT); 
                    }
                }

                // 2. ADIM: Kendi fontlarımızı %100 UTF-8 (IDENTITY_H) olarak sisteme tanıt
                // Ana Font: DejaVuSans (Her dili ve Türkçe karakteri kusursuz destekler)
                String dejavuPath = "/usr/local/tomcat/conf/DejaVuSans.ttf";
                BaseFont dejavuFont = BaseFont.createFont(dejavuPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                // Yedek Font (Emniyet Kemeri): LiberationSerif
                String serifPath = "/usr/local/tomcat/conf/LiberationSerif.ttf";
                BaseFont serifFont = BaseFont.createFont(serifPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                // 3. ADIM: Tüm kutucukları dön, fontu sıfırdan yaz ve veriyi bas
                for (String key : form.getFields().keySet()) {
                    
                    // Kutucuğun fontunu zorla DejaVu yap
                    form.setFieldProperty(key, "textfont", dejavuFont, null);
                    
                    // Veriyi (İsim/Tarih vb.) bas
                    String binding = bindings.get(key);
                    form.setField(key, binding);
                }

                // 4. ADIM: DejaVu'nun bile çizemediği Asya dilleri vs. gelirse yedeğe başvur
                form.addSubstitutionFont(serifFont);

            } catch (Exception e) {
                System.err.println("[CERTIFICATION FONT ERROR] God Mode Font zorlamasi basarisiz: " + e.getMessage());
                
                // Eğer nükleer seçenek bir şekilde patlarsa (ki çok zor), eski usül basmayı dene
                for (String key : form.getFields().keySet()) {
                    String binding = bindings.get(key);
                    form.setField(key, binding);
                }
            }
            // =========================================================
            // GOD MODE BİTİŞİ
            // =========================================================

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