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
            // NİHAİ ÇÖZÜM: HEM ZORLAMA HEM YEDEKLEME (FALLBACK)
            // =========================================================
            try {
                // 1. Ana Tankımız (Acrobat kilidini kırmak için)
                String dejavuPath = "/usr/local/tomcat/conf/DejaVuSans.ttf";
                BaseFont dejavuFont = BaseFont.createFont(dejavuPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                // 2. Yedeklerimiz (Olur da DejaVu'da harf olmazsa diye)
                // İstersen daha önce kopyaladığın Liberation veya Times'ı da yedek yapabilirsin
                String serifPath = "/usr/local/tomcat/conf/LiberationSerif.ttf";
                BaseFont serifFont = BaseFont.createFont(serifPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

                // PDF'teki tüm form alanlarını dön
                for (String key : form.getFields().keySet()) {
                    
                    // ACROBAT'IN KİLİDİNİ KIR: Kutuyu zorla DejaVu yap
                    form.setFieldProperty(key, "textfont", dejavuFont, null); 
                    
                    String binding = bindings.get(key);
                    form.setField(key, binding);
                }

                // EMNİYET KEMERİ: Eğer DejaVu harfi çizmeyi başaramazsa, sisteme yedek fontları ver
                // iText kutuyu DejaVu ile basmaya çalışırken harfi bulamazsa otomatik bunlara başvurur.
                form.addSubstitutionFont(serifFont);
                // form.addSubstitutionFont(cjkFont); // Çince/Asya fontun varsa buraya ekleyebilirsin

            } catch (Exception e) {
                System.err.println("[CERTIFICATION FONT ERROR] Nihai font cozumu basarisiz: " + e.getMessage());
            }
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
