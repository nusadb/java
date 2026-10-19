package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NusaSequenceSupportTest {

    @Test
    void nextvalNamesTheSequenceAsTheServerStoresIt() {
        assertEquals("nextval('gen_auto_seq')", NusaSequenceSupport.INSTANCE.getSelectSequenceNextValString("gen_auto_SEQ"));
        assertEquals("nextval('ids')", NusaSequenceSupport.INSTANCE.getSelectSequenceNextValString("public.ids"));
        assertEquals("nextval('Mixed_Seq')", NusaSequenceSupport.INSTANCE.getSelectSequenceNextValString("\"Mixed_Seq\""));
        assertEquals("nextval('o''brien')", NusaSequenceSupport.INSTANCE.getSelectSequenceNextValString("o'brien"));
        assertEquals("select currval('ids')", "select " + NusaSequenceSupport.INSTANCE.getSelectSequencePreviousValString("IDS"));
    }
}
