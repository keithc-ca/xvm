module AddressBookDB
        incorporates oodb.Database
    {
    package oodb import oodb.xtclang.org;

    interface AddressBookSchema
            extends oodb.RootSchema
        {
        @RO Contacts     contacts;
        @RO oodb.DBCounter requestCount;
        }

    /**
     * This is the interface that will get injected.
     */
    typedef (oodb.Connection<AddressBookSchema> + AddressBookSchema) Connection;

    /**
     * This is the interface that will come back from createTransaction.
     */
    typedef (oodb.Transaction<AddressBookSchema> + AddressBookSchema) Transaction;

    mixin Contacts
            into oodb.DBMap<String, Contact>
        {
        void addContact(Contact contact)
            {
            String name = contact.rolodexName;
            if (contains(name))
                {
                throw new IllegalState($"already exists {name}");
                }
            put(name, contact);
            }

        void addPhone(String name, Phone phone)
            {
            if (Contact oldContact := get(name))
                {
                Contact newContact = oldContact.withPhone(phone);
                put(name, newContact);
                }
            else
                {
                throw new IllegalState($"no contact {name}");
                }
            }
        }

    const Contact(String firstName, String lastName, Email[] emails = [], Phone[] phones = [])
        {
        // @oodb.PKey
        String rolodexName.get()
            {
            return $"{lastName}, {firstName}";
            }

        String fullName.get()
            {
            return $"{firstName} {lastName}";
            }

        Contact withPhone(Phone phone)
            {
            return new Contact(firstName, lastName, emails, phones + phone);
            }

        Contact addEmail(Email email)
            {
            return new Contact(firstName, lastName, emails + email, phones);
            }
        }

    enum EmailCat {Home, Work, Other}

    const Email(EmailCat category, String email);

    enum PhoneCat {Home, Work, Mobile, Fax, Other}

    const Phone(PhoneCat category, String number);
    }