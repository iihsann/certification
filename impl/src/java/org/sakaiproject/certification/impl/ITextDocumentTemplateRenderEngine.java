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

// bunu ekle
import com.itextpdf.text.pdf.BaseFont;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        this.documentTemplateService = (DocumentTemplateService)dts;
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

            Set<String> fieldKeys = fields.keySet();
            Set<String> textFieldKeys = new HashSet<>();

            for (String key : fieldKeys) {
                if (acroFields.getFieldType(key) == (AcroFields.FIELD_TYPE_TEXT)) {
                    textFieldKeys.add(key);
                }
            }

            return textFieldKeys;

        } catch (IOException e) {
            throw new TemplateReadException (e);
        }
    }

    public InputStream render(DocumentTemplate template, Map<String, String> bindings) throws TemplateReadException {
        assertCorrectType(template);

        try {
            PdfReader reader = new PdfReader (certificateService.getTemplateFileInputStream(template.getResourceId()));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper (reader, baos);

            stamper.setFormFlattening(true);
            stamper.setFreeTextFlattening(true);

            AcroFields form = stamper.getAcroFields();

            // =========================================================
            // ÇOKLU TÜRKÇE FONT YEDEKLEMESİ (FALLBACK) BAŞLANGICI
            // =========================================================
            try {
                // Dockerfile aracılığıyla Tomcat'in içine gömdüğümüz font dosyalarının yolları
                String arialPath = "/usr/local/tomcat/conf/arial.ttf";
                String timesPath = "/usr/local/tomcat/conf/times.ttf";
                String dejavuPath = "/usr/local/tomcat/conf/DejaVuSans.ttf";

                // Fontları IDENTITY_H (UTF-8 / Unicode destekli) ve gömülü (EMBEDDED) olarak yüklüyoruz
                BaseFont arialFont = BaseFont.createFont(arialPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                BaseFont timesFont = BaseFont.createFont(timesPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                BaseFont dejavuFont = BaseFont.createFont(dejavuPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                // Sırayla yedek font olarak forma ekliyoruz.
                // iText, PDF'in kendi fontunda bulamadığı her bir harf (Ö, Ç, Ş, vb.) için
                // sırasıyla Arial, Times ve DejaVu fontlarına başvuracaktır.
                form.addSubstitutionFont(arialFont);
                form.addSubstitutionFont(timesFont);
                form.addSubstitutionFont(dejavuFont);

            } catch (Exception e) {
                // Fontlar herhangi bir sebeple yüklenemezse sistemi çökertmeyip sadece logluyoruz. Log kütüphanesi yerine standart error output kullanıldı (Derleme hatasını engeller)
                System.err.println("[CERTIFICATION FONT ERROR] Turkce fontlar yuklenemedi: " + e.getMessage());
            }
            // =========================================================
            // ÇOKLU TÜRKÇE FONT YEDEKLEMESİ BİTİŞİ
            // =========================================================

            for (String key : form.getFields().keySet()) {
                String binding = bindings.get(key);
                form.setField(key, binding);
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
            PdfReader reader = new PdfReader (certificateService.getTemplateFileInputStream(template.getResourceId()));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper (reader, baos);

            stamper.setFormFlattening(true);
            stamper.setFreeTextFlattening(true);
            stamper.close();

            return new ByteArrayInputStream(baos.toByteArray());

        } catch (IOException | DocumentException e) {
            throw new TemplateReadException(e);
        }
    }
}
