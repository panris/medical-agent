#!/usr/bin/env python3
"""
医学知识库种子数据导入脚本
用法: python3 seed_knowledge.py --api-url http://localhost:8080/api/v1/config/llm
或直接插入 PostgreSQL:
  python3 seed_knowledge.py --pg "postgresql://medical:medical_dev_password@localhost:5432/medical_agent"
"""
import json
import sys
import argparse

# 最小可用医学 FAQ 集（50 条）
SEED_DATA = [
    {"content": "头痛伴恶心呕吐可能是偏头痛的典型表现。偏头痛常表现为单侧搏动性头痛，可持续4-72小时，伴恶心、呕吐、畏光、畏声。诱因包括压力、睡眠不足、特定食物（如巧克力、奶酪、红酒）等。建议保持规律作息，避免已知诱因，急性发作可使用布洛芬等止痛药，频繁发作需就医预防性治疗。", "category": "神经内科", "source_file": "headache_guide.md"},
    {"content": "紧张性头痛是最常见的头痛类型，表现为双侧压迫感或紧箍感，轻至中度疼痛，不伴恶心呕吐。常与精神压力、焦虑、疲劳、不良姿势有关。治疗以休息、放松训练为主，必要时可服用对乙酰氨基酚或布洛芬。", "category": "神经内科", "source_file": "headache_guide.md"},
    {"content": "突发剧烈头痛（雷击样头痛）需紧急就医，可能是蛛网膜下腔出血的表现。伴随颈部僵硬、意识改变、呕吐时更应警惕。这是神经科急症，需立即CT检查排除出血。", "category": "急诊科", "source_file": "headache_guide.md"},
    {"content": "发热伴咳嗽、咽痛、鼻塞流涕常见于上呼吸道感染（普通感冒）。多为病毒感染，自限性，一般5-7天好转。治疗以对症为主：退热用对乙酰氨基酚，鼻塞用生理盐水冲洗，多休息多饮水。若体温超过39°C持续3天以上或出现呼吸困难需就医。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
    {"content": "咳嗽超过8周为慢性咳嗽，常见原因包括：咳嗽变异性哮喘、上气道咳嗽综合征（鼻后滴漏）、胃食管反流、嗜酸粒细胞性支气管炎。需逐一排查，建议呼吸内科就诊。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
    {"content": "咳铁锈色痰是肺炎链球菌肺炎的典型表现，常伴高热、寒战、胸痛。胸部X线可见肺叶实变影。需要抗生素治疗，建议及时就医。老年人和免疫力低下者风险更高。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
    {"content": "胃部不适伴反酸、烧心是胃食管反流病（GERD）的典型症状。饱餐后或平卧时加重。生活方式调整：少食多餐、避免辛辣油腻、睡前3小时禁食、抬高床头。药物治疗包括质子泵抑制剂（如奥美拉唑）。长期反流需胃镜检查排除Barrett食管。", "category": "消化内科", "source_file": "gi_guide.md"},
    {"content": "急性腹痛需鉴别诊断。右上腹压痛伴发热可能为急性胆囊炎；转移性右下腹痛可能为急性阑尾炎；全腹压痛反跳痛可能为消化道穿孔。以上情况均需急诊就医，不要自行服用止痛药以免掩盖病情。", "category": "急诊科", "source_file": "gi_guide.md"},
    {"content": "慢性胃炎常见症状包括上腹部隐痛、胀满、嗳气、食欲减退。幽门螺杆菌感染是重要病因，可通过碳13或碳14呼气试验检测。阳性者建议根除治疗（四联疗法：PPI+铋剂+两种抗生素，疗程14天）。", "category": "消化内科", "source_file": "gi_guide.md"},
    {"content": "腹泻伴黏液脓血便、发热、腹痛需考虑细菌性痢疾或炎症性肠病。水样腹泻伴呕吐常见于急性胃肠炎（病毒或细菌感染）。腹泻时注意补液，口服补液盐（ORS）首选。血便或持续超过3天需就医。", "category": "消化内科", "source_file": "gi_guide.md"},
    {"content": "胸闷气短活动后加重，需警惕心力衰竭。左心衰表现为劳力性呼吸困难、夜间阵发性呼吸困难、端坐呼吸。右心衰表现为下肢水肿、肝大、腹水。需心脏彩超评估心功能，BNP/NT-proBNP协助诊断。", "category": "心内科", "source_file": "cardiology_guide.md"},
    {"content": "胸痛需紧急鉴别：压榨性胸痛向左肩左臂放射，伴大汗、濒死感，需首先排除急性心肌梗死。立即含服硝酸甘油，拨打120。心梗的黄金救治时间为发病后120分钟内（门球时间）。", "category": "急诊科", "source_file": "cardiology_guide.md"},
    {"content": "心悸常见原因：窦性心动过速（紧张、运动、咖啡因）、早搏（房性/室性）、心房颤动、阵发性室上性心动过速。偶尔心悸无其他症状多为良性，频发或伴头晕黑矇需24小时动态心电图检查。", "category": "心内科", "source_file": "cardiology_guide.md"},
    {"content": "高血压诊断标准：非同日三次测量收缩压≥140mmHg和/或舒张压≥90mmHg。治疗目标一般<140/90mmHg，糖尿病或肾病患者<130/80mmHg。生活方式干预：低盐（<6g/天）、减重、运动、戒烟限酒。药物治疗需个体化，不可自行停药。", "category": "心内科", "source_file": "cardiology_guide.md"},
    {"content": "失眠表现为入睡困难（>30分钟入睡）、睡眠维持困难（夜间觉醒≥2次）、早醒。短期失眠（<3个月）常与应激事件有关，慢性失眠（≥3个月）需综合治疗。睡眠卫生：固定作息、避免日间小睡、卧室仅用于睡眠、睡前避免蓝屏和咖啡因。认知行为治疗（CBT-I）为一线治疗。", "category": "精神科", "source_file": "sleep_guide.md"},
    {"content": "皮肤起红疹伴瘙痒常见原因：荨麻疹（风团，来去匆匆，24小时内消退）、湿疹（红斑丘疹渗出，反复发作）、接触性皮炎（接触部位边界清楚的红斑水疱）、药疹（用药后出现，对称分布）。抗组胺药（如氯雷他定）可缓解瘙痒。严重或持续不退需皮肤科就诊。", "category": "皮肤科", "source_file": "dermatology_guide.md"},
    {"content": "关节红肿热痛急性发作，尤其第一跖趾关节（大脚趾），需考虑痛风。血尿酸>420μmol/L支持诊断。急性期用秋水仙碱或非甾体抗炎药，忌用降尿酸药物（会加重炎症）。缓解期需长期降尿酸治疗，控制饮食（低嘌呤、戒酒、多饮水）。", "category": "风湿免疫科", "source_file": "rheumatology_guide.md"},
    {"content": "腰痛伴向下肢放射痛（坐骨神经痛）可能为腰椎间盘突出。直腿抬高试验阳性支持诊断。急性期卧床休息、非甾体抗炎药、肌肉松弛剂。出现马尾综合征（大小便障碍、鞍区麻木）需紧急手术。保守治疗6-8周无效考虑微创介入。", "category": "骨科", "source_file": "orthopedics_guide.md"},
    {"content": "糖尿病典型症状：多饮、多食、多尿、体重下降（三多一少）。诊断标准：空腹血糖≥7.0mmol/L，或OGTT 2小时血糖≥11.1mmol/L，或HbA1c≥6.5%。2型糖尿病一线药物为二甲双胍。生活方式干预是基础：控制饮食、规律运动、减重。", "category": "内分泌科", "source_file": "endocrine_guide.md"},
    {"content": "甲状腺功能亢进（甲亢）常见症状：心悸、多汗、怕热、手抖、体重下降、大便次数增多、月经紊乱。Graves病最常见，可伴突眼。确诊需甲状腺功能检查（TSH降低、FT3/FT4升高）和TRAb。治疗选择：抗甲状腺药物、放射性碘、手术。", "category": "内分泌科", "source_file": "endocrine_guide.md"},
    {"content": "贫血常见表现：乏力、头晕、面色苍白、心悸、活动后气短。缺铁性贫血最常见，原因包括月经过多、消化道出血、铁摄入不足。补铁治疗同时需查明病因。网织红细胞计数有助于鉴别贫血类型。", "category": "血液科", "source_file": "hematology_guide.md"},
    {"content": "尿频尿急尿痛是尿路感染的典型三联征，女性多见。尿常规可见白细胞和细菌。治疗用抗生素（如左氧氟沙星或头孢类），疗程3-7天。反复发作需排查泌尿系结构异常、糖尿病等基础病。多饮水、不憋尿、注意个人卫生可预防。", "category": "肾内科", "source_file": "nephrology_guide.md"},
    {"content": "视力突然下降需紧急就诊，可能原因：视网膜中央动脉阻塞（无痛性视力骤降，黄金救治时间90分钟）、急性闭角型青光眼（眼痛头痛视力下降伴恶心呕吐）、玻璃体积血、视神经炎。任何突发视力变化都应视为眼科急诊。", "category": "眼科", "source_file": "ophthalmology_guide.md"},
    {"content": "突发单侧听力下降伴耳鸣需警惕突发性耳聋，治疗黄金时间为发病后72小时内。方案包括糖皮质激素、改善微循环药物、高压氧等。延误治疗恢复率显著降低，需耳鼻喉科急诊。", "category": "耳鼻喉科", "source_file": "ent_guide.md"},
    {"content": "过敏反应轻度：皮肤荨麻疹、瘙痒。中度：血管性水肿、呼吸困难、腹痛。重度（过敏性休克）：血压骤降、意识丧失、喉头水肿窒息。重度过敏需立即肌注肾上腺素（大腿外侧），拨打120。有过敏史者应随身携带肾上腺素自动注射器。", "category": "急诊科", "source_file": "allergy_guide.md"},
    {"content": "发热是体温超过37.3°C。低热37.3-38°C，中热38.1-39°C，高热39.1-41°C，超高热>41°C。不明原因发热（FUO）定义为体温>38.3°C持续3周以上，经1周检查未确诊。常见病因：感染、肿瘤、自身免疫病。退热药不能替代病因治疗。", "category": "感染科", "source_file": "fever_guide.md"},
    {"content": "咳嗽伴大量黄脓痰、发热、胸痛提示肺炎可能。社区获得性肺炎常见病原体：肺炎链球菌、流感嗜血杆菌、支原体、衣原体。CURB-65评分用于评估严重程度和是否需住院。老年人肺炎症状可不典型，可能仅表现为食欲下降、意识模糊。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
    {"content": "脂肪肝是我国最常见的慢性肝病。非酒精性脂肪肝与肥胖、糖尿病、高脂血症密切相关。多数无症状，体检B超发现。治疗以生活方式干预为主：减重5-10%、规律有氧运动、控制血糖血脂。单纯性脂肪肝预后好，但可进展为脂肪性肝炎、肝硬化。", "category": "消化内科", "source_file": "gi_guide.md"},
    {"content": "肩关节疼痛活动受限，尤其是外展外旋受限，需考虑肩周炎（冻结肩）。50岁左右高发，女性多于男性。自然病程1-3年，分冻结进行期、冻结期、解冻期。急性期止痛为主，慢性期功能锻炼为关键。不建议长期制动。", "category": "骨科", "source_file": "orthopedics_guide.md"},
    {"content": "颈椎病常见类型：神经根型（上肢放射痛麻木）、脊髓型（四肢无力行走不稳，最严重）、椎动脉型（头晕）、交感型。长期低头工作是重要诱因。预防：正确坐姿、避免长时间低头、颈部肌肉锻炼。脊髓型需早期手术干预。", "category": "骨科", "source_file": "orthopedics_guide.md"},
    {"content": "抑郁症核心症状：持续情绪低落、兴趣丧失、精力减退，持续2周以上。伴随症状：睡眠障碍、食欲改变、注意力下降、自责自罪、消极念头。自杀风险评估至关重要。治疗：药物治疗（SSRI类首选）+心理治疗。不可自行停药，需逐步减量。", "category": "精神科", "source_file": "psychiatry_guide.md"},
    {"content": "焦虑障碍表现：过度担忧、坐立不安、肌肉紧张、心悸、出汗、睡眠障碍。广泛性焦虑持续6个月以上。惊恐障碍为反复发作的强烈恐惧伴心悸、胸闷、窒息感，常被误诊为心脏病。治疗：SSRI类药物+认知行为治疗（CBT）。", "category": "精神科", "source_file": "psychiatry_guide.md"},
    {"content": "儿童发热处理：3个月以下婴儿任何发热需立即就医。3-6个月体温>39°C需就医。6个月以上可观察，精神状态好的发热不必过度退热。退热药选择：对乙酰氨基酚（>2个月）或布洛芬（>6个月），忌用阿司匹林（Reye综合征风险）。物理降温温水擦浴，忌酒精擦浴。", "category": "儿科", "source_file": "pediatrics_guide.md"},
    {"content": "孕期常见不适：孕早期恶心呕吐（晨吐）多为正常，严重呕吐（妊娠剧吐）需输液治疗。孕中晚期腿抽筋可补钙。妊娠期高血压（血压≥140/90mmHg伴蛋白尿）需密切监测，可发展为子痫。规律产检至关重要。", "category": "产科", "source_file": "obstetrics_guide.md"},
    {"content": "便血需鉴别：鲜血便多为肛周疾病（痔疮、肛裂），暗红色血便可能为下消化道出血，黑便（柏油样便）为上消化道出血。40岁以上首次便血或伴排便习惯改变需肠镜检查排除结直肠癌。", "category": "消化内科", "source_file": "gi_guide.md"},
    {"content": "慢性阻塞性肺疾病（COPD）表现为长期咳嗽咳痰伴活动后气短，常见于长期吸烟者。肺功能检查为诊断金标准：吸入支气管扩张剂后FEV1/FVC<0.7。治疗：戒烟（最重要）、支气管扩张剂、糖皮质激素。急性加重期需抗感染治疗。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
    {"content": "哮喘典型三联征：反复发作性喘息、呼吸困难、咳嗽，夜间和清晨加重，可自行或用药后缓解。诊断：支气管舒张试验阳性或呼气峰流速日间变异率>20%。治疗：长期控制用吸入性糖皮质激素（ICS），急性发作用短效β2受体激动剂（SABA）。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
    {"content": "肝功能异常常见原因：病毒性肝炎（乙肝、丙肝）、脂肪肝、酒精性肝病、药物性肝损伤、自身免疫性肝炎。ALT和AST升高提示肝细胞损伤，ALP和GGT升高提示胆汁淤积。发现肝功能异常需进一步检查病因，不可仅护肝治疗。", "category": "消化内科", "source_file": "gi_guide.md"},
    {"content": "肾结石典型表现：突发腰部剧烈绞痛，向下腹部和会阴部放射，伴恶心呕吐、血尿。超声或CT可确诊。5mm以下结石多可自行排出，大量饮水、适当运动有助于排石。大于10mm或伴梗阻感染需泌尿外科干预。预防：每日饮水2L以上。", "category": "泌尿外科", "source_file": "urology_guide.md"},
    {"content": "脑卒中（中风）识别FAST原则：Face面部不对称、Arm上肢无力、Speech言语不清、Time立即拨打120。缺血性卒中溶栓时间窗为发病4.5小时内，每延迟1分钟约损失190万个神经元。出血性卒中需控制血压、降低颅压。时间就是大脑。", "category": "急诊科", "source_file": "neurology_guide.md"},
    {"content": "系统性红斑狼疮（SLE）多见于年轻女性，表现为面部蝶形红斑、关节痛、脱发、口腔溃疡、光过敏。可累及肾脏（狼疮性肾炎）、血液系统、中枢神经系统。抗核抗体（ANA）和抗dsDNA抗体有助于诊断。需风湿免疫科长期随访治疗。", "category": "风湿免疫科", "source_file": "rheumatology_guide.md"},
    {"content": "骨质疏松被称为沉默的杀手，早期无症状，骨折为首发表现。高危人群：绝经后女性、老年人、长期使用糖皮质激素者。骨密度检查（DEXA）T值≤-2.5诊断骨质疏松。预防：充足钙（1000-1200mg/天）和维生素D（800-1000IU/天）摄入、负重运动、防跌倒。", "category": "骨科", "source_file": "orthopedics_guide.md"},
    {"content": "耳鸣常见原因：噪音暴露、年龄相关听力下降、中耳炎、梅尼埃病、药物（如阿司匹林、庆大霉素）。搏动性耳鸣需排除血管病变。突发单侧耳鸣伴听力下降需及时就诊。避免噪音、保证睡眠、减少咖啡因可改善。", "category": "耳鼻喉科", "source_file": "ent_guide.md"},
    {"content": "鼻出血处理：坐位头略前倾（不要后仰，避免血液流入气管），捏住鼻翼（鼻孔两侧软骨部分）持续压迫10-15分钟，冰敷鼻根部。频繁鼻出血或后鼻孔出血需耳鼻喉科检查排除肿瘤。高血压患者鼻出血需同时控制血压。", "category": "耳鼻喉科", "source_file": "ent_guide.md"},
    {"content": "前列腺增生常见于50岁以上男性，表现为尿频、尿急、夜尿增多、排尿困难、尿线变细。IPSS评分评估症状严重程度。药物治疗：α受体阻滞剂（改善排尿）+5α还原酶抑制剂（缩小前列腺）。药物治疗无效或出现并发症（尿潴留、反复感染）考虑手术。", "category": "泌尿外科", "source_file": "urology_guide.md"},
    {"content": "带状疱疹典型表现：单侧皮肤沿神经分布的簇集性水疱，伴明显神经痛。好发于胸背部和腰腹部。早期（72小时内）抗病毒治疗（阿昔洛韦、伐昔洛韦）可缩短病程、减少后遗神经痛。后遗神经痛治疗较困难，可加巴喷丁或普瑞巴林。", "category": "皮肤科", "source_file": "dermatology_guide.md"},
    {"content": "消化道出血表现取决于出血量和速度：呕血（上消化道）、黑便（上消化道，出血量>50ml）、鲜血便（下消化道）。失血性休克表现为心悸、头晕、出冷汗、血压下降。大量出血需立即输液输血、内镜止血。禁食，不要自行服用止血药。", "category": "急诊科", "source_file": "gi_guide.md"},
    {"content": "帕金森病四大运动症状：静止性震颤、肌强直、运动迟缓、姿势步态障碍。常从一侧上肢开始，逐渐进展至对侧。非运动症状可先于运动症状出现：嗅觉减退、便秘、睡眠障碍（RBD）、抑郁。治疗以左旋多巴为核心，配合康复训练。", "category": "神经内科", "source_file": "neurology_guide.md"},
    {"content": "胸膜炎性胸痛特点：深呼吸或咳嗽时加重的锐痛，提示胸膜受累。常见原因：肺炎旁积液、肺栓塞、结核性胸膜炎、恶性肿瘤。肺栓塞表现为突发胸痛、呼吸困难、咯血三联征，有下肢深静脉血栓史或长期制动者为高危人群，需CT肺动脉造影（CTPA）确诊。", "category": "呼吸内科", "source_file": "respiratory_guide.md"},
]


def insert_via_pg(conn_str):
    """直接插入 PostgreSQL"""
    import psycopg2
    conn = psycopg2.connect(conn_str)
    cur = conn.cursor()
    inserted = 0
    for i, item in enumerate(SEED_DATA):
        metadata = json.dumps({"category": item["category"], "source_type": "faq"}, ensure_ascii=False)
        cur.execute(
            "INSERT INTO knowledge_base (source_file, chunk_index, content, metadata) VALUES (%s, %s, %s, %s)",
            (item["source_file"], i % 10, item["content"], metadata)
        )
        inserted += 1
    conn.commit()
    cur.close()
    conn.close()
    print(f"✅ Inserted {inserted} records into knowledge_base")
    print("⚠️  Run embedding generation next: python3 seed_knowledge.py --embed --pg <conn_str>")


def generate_embeddings(conn_str, base_url, api_key, model):
    """为已有记录生成 embedding 向量"""
    import psycopg2
    import requests

    conn = psycopg2.connect(conn_str)
    cur = conn.cursor()
    cur.execute("SELECT id, content FROM knowledge_base WHERE embedding IS NULL")
    rows = cur.fetchall()
    print(f"Generating embeddings for {len(rows)} records...")

    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    updated = 0
    for row_id, content in rows:
        try:
            resp = requests.post(
                f"{base_url}/embeddings",
                json={"model": model, "input": content[:8192]},
                headers=headers,
                timeout=30
            )
            if resp.status_code == 200:
                embedding = resp.json()["data"][0]["embedding"]
                vec_str = "[" + ",".join(str(x) for x in embedding) + "]"
                cur.execute("UPDATE knowledge_base SET embedding = %s::vector WHERE id = %s", (vec_str, row_id))
                updated += 1
                if updated % 10 == 0:
                    conn.commit()
                    print(f"  {updated}/{len(rows)} embedded")
        except Exception as e:
            print(f"  Failed id={row_id}: {e}")

    conn.commit()
    cur.close()
    conn.close()
    print(f"✅ Updated {updated} embeddings")


def main():
    parser = argparse.ArgumentParser(description="Medical knowledge base seed data")
    parser.add_argument("--pg", help="PostgreSQL connection string")
    parser.add_argument("--embed", action="store_true", help="Generate embeddings for existing records")
    parser.add_argument("--base-url", default="https://api.openai.com/v1", help="Embedding API base URL")
    parser.add_argument("--api-key", default="", help="Embedding API key")
    parser.add_argument("--model", default="text-embedding-3-small", help="Embedding model")
    args = parser.parse_args()

    if not args.pg:
        print("❌ --pg connection string required")
        print("Example: python3 seed_knowledge.py --pg 'postgresql://medical:medical_dev_password@localhost:5432/medical_agent'")
        sys.exit(1)

    if args.embed:
        if not args.api_key:
            print("❌ --api-key required for embedding generation")
            sys.exit(1)
        generate_embeddings(args.pg, args.base_url, args.api_key, args.model)
    else:
        insert_via_pg(args.pg)


if __name__ == "__main__":
    main()
