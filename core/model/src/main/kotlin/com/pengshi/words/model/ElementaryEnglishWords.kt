package com.pengshi.words.model

import java.util.Locale

/** Conservative elementary vocabulary excluded only from automatic selection in the exam deck. */
object ElementaryEnglishWords {
    const val KAOYAN_SOURCE_FILE = "kaoyan-shared-2024.csv"

    private val words = """
        a an the be am is are was were been being to of and or but if so because as at by for
        from in into on onto out over under up down off with without about after before between
        behind beside near far around across through during since until than then there here where
        when why how what which who whom whose this that these those it its i me my mine you your
        yours he him his she her hers we us our ours they them their theirs myself yourself himself
        herself itself ourselves themselves someone anyone everyone nobody somebody something anything
        everything nothing each every all both either neither many much more most few less little
        some any no not yes only just also very too enough other another same such own one two three
        four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen
        eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred thousand first
        second third last next today tomorrow yesterday now soon early late morning afternoon evening
        night day week month year hour minute time monday tuesday wednesday thursday friday saturday
        sunday january february march april may june july august september october november december
        can could may might must shall should will would do does did done doing have has had having
        get gets got go goes went gone come came make made take took give gave put set let keep kept
        see saw seen look watch hear heard listen say said tell told talk speak spoke read write wrote
        know knew known think thought feel felt want need like love hate try help use used work play
        study learn teach ask answer call find found show open close start begin end stop finish wait
        leave left stay live move walk run ran jump sit sat stand stood turn bring brought buy bought
        sell sold pay paid spend spent eat ate eaten drink drank sleep slept wake wash cook clean wear
        wore worn cut draw drew sing sang dance smile laugh cry meet met send sent build built break
        broke broken grow grew grown change choose chose chosen remember forget forgot forgotten
        big small large tiny long short tall high low old young new good better best bad worse worst
        nice kind happy sad angry sorry glad afraid easy hard difficult simple right wrong true false
        hot cold warm cool wet dry clean dirty full empty rich poor strong weak fast slow quick quiet
        loud soft light dark white black red blue green yellow orange pink purple brown grey gray
        beautiful pretty ugly busy free ready tired hungry thirsty sick healthy safe dangerous
        important interesting funny different special common real sure clear fine great wonderful
        people person man men woman women boy girl child children baby friend family mother father
        mom dad mum parent brother sister son daughter grandma grandfather grandmother grandpa uncle
        aunt cousin husband wife teacher student pupil doctor nurse farmer driver worker police
        name age number word sentence story question problem idea thing stuff part place way side
        world earth country city town village street road river sea ocean lake mountain tree flower
        grass leaf leaves sky sun moon star cloud rain snow wind weather spring summer autumn winter
        animal dog cat bird fish horse cow pig sheep chicken duck rabbit mouse mice elephant tiger
        lion monkey bear panda snake frog bee butterfly
        house home room bedroom bathroom kitchen door window wall floor table chair desk bed sofa
        lamp light clock picture box bag key phone computer television tv radio camera car bus train
        plane airplane bike bicycle boat ship school classroom library park shop store market hospital
        bank station airport hotel restaurant farm garden beach playground
        book notebook paper pen pencil ruler eraser desk lesson class homework exam test language
        english chinese math science music art sport game ball football basketball tennis swim
        water milk tea coffee juice bread rice noodle noodles egg meat beef pork chicken fish fruit
        apple banana orange pear peach grape watermelon lemon strawberry vegetable potato tomato
        carrot onion cabbage salt sugar cake candy chocolate ice breakfast lunch dinner meal
        head hair face eye eyes ear ears nose mouth tooth teeth tongue neck shoulder arm hand finger
        leg foot feet knee heart body back stomach skin blood
        shirt coat dress skirt shoes shoe sock socks hat cap pants trousers clothes
        money price gift food color size shape line circle square left right north south east west
        good morning good night thank thanks please hello hi goodbye bye okay ok
    """.trimIndent().split(Regex("\\s+")).toSet()

    fun contains(spelling: String): Boolean = spelling.trim().lowercase(Locale.ROOT) in words
}
