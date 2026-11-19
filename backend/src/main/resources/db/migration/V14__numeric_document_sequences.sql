-- Keep the old prefixed sequences and every stored reference intact. These
-- independent sequences allocate numbers globally per document type.
DO $$
DECLARE
    document record;
    first_number numeric;
BEGIN
    FOR document IN
        SELECT * FROM (VALUES
            ('purchase_document_seq', 'purchases', 'purchase_number'),
            ('sale_document_seq', 'sales', 'sales_number'),
            ('transfer_document_seq', 'stock_transfers', 'transfer_number'),
            ('adjustment_document_seq', 'stock_adjustments', 'adjustment_number'),
            ('item_import_document_seq', 'item_import_batches', 'import_number'),
            ('supplier_payment_document_seq', 'supplier_payments', 'payment_number'),
            ('sale_modification_document_seq', 'sale_modifications', 'modification_number'),
            ('sale_cancellation_document_seq', 'sale_cancellations', 'cancellation_number')
        ) AS types(sequence_name, table_name, number_column)
    LOOP
        -- Existing numeric documents, if any, reserve their numbers across
        -- stores. Prefixed strings are a separate namespace.
        EXECUTE format(
            'SELECT coalesce(max(%1$I::numeric), 0) + 1 FROM %2$I WHERE %1$I ~ %3$L',
            document.number_column, document.table_name, '^[0-9]+$'
        ) INTO first_number;

        EXECUTE format('CREATE SEQUENCE %I AS bigint START WITH %s',
            document.sequence_name, first_number);
    END LOOP;
END $$;
